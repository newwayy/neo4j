/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.api.impl.schema.populator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.neo4j.collection.PrimitiveLongCollections;
import org.neo4j.configuration.Config;
import org.neo4j.internal.kernel.api.IndexOrder;
import org.neo4j.internal.kernel.api.IndexQuery;
import org.neo4j.internal.schema.SchemaDescriptor;
import org.neo4j.internal.schema.SchemaDescriptorFactory;
import org.neo4j.io.IOUtils;
import org.neo4j.io.fs.DefaultFileSystemAbstraction;
import org.neo4j.kernel.api.impl.index.storage.DirectoryFactory;
import org.neo4j.kernel.api.impl.index.storage.PartitionedIndexStorage;
import org.neo4j.kernel.api.impl.schema.LuceneSchemaIndexBuilder;
import org.neo4j.kernel.api.impl.schema.SchemaIndex;
import org.neo4j.kernel.api.index.IndexReader;
import org.neo4j.kernel.api.index.IndexSample;
import org.neo4j.kernel.impl.api.index.sampling.IndexSamplingConfig;
import org.neo4j.kernel.impl.index.schema.IndexDescriptor;
import org.neo4j.kernel.impl.index.schema.IndexDescriptorFactory;
import org.neo4j.kernel.impl.index.schema.NodeValueIterator;
import org.neo4j.storageengine.api.IndexEntryUpdate;
import org.neo4j.test.extension.DefaultFileSystemExtension;
import org.neo4j.test.extension.Inject;
import org.neo4j.test.extension.TestDirectoryExtension;
import org.neo4j.test.rule.TestDirectory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.neo4j.internal.kernel.api.QueryContext.NULL_CONTEXT;
import static org.neo4j.kernel.api.index.IndexQueryHelper.add;

@ExtendWith( {DefaultFileSystemExtension.class, TestDirectoryExtension.class} )
class NonUniqueDatabaseIndexPopulatorTest
{
    private final DirectoryFactory dirFactory = new DirectoryFactory.InMemoryDirectoryFactory();
    @Inject
    private TestDirectory testDir;
    @Inject
    private DefaultFileSystemAbstraction fileSystem;

    private SchemaIndex index;
    private NonUniqueLuceneIndexPopulator populator;
    private final SchemaDescriptor labelSchemaDescriptor = SchemaDescriptorFactory.forLabel( 0, 0 );

    @BeforeEach
    void setUp()
    {
        File folder = testDir.directory( "folder" );
        PartitionedIndexStorage indexStorage = new PartitionedIndexStorage( dirFactory, fileSystem, folder );

        IndexDescriptor descriptor = IndexDescriptorFactory.forSchema( labelSchemaDescriptor );
        index = LuceneSchemaIndexBuilder.create( descriptor, Config.defaults() )
                                        .withIndexStorage( indexStorage )
                                        .build();
    }

    @AfterEach
    void tearDown() throws Exception
    {
        if ( populator != null )
        {
            populator.close( false );
        }
        IOUtils.closeAll( index, dirFactory );
    }

    @Test
    void sampleEmptyIndex() throws IOException
    {
        populator = newPopulator();

        IndexSample sample = populator.sampleResult();

        assertEquals( new IndexSample(), sample );
    }

    @Test
    void sampleIncludedUpdates() throws Exception
    {
        populator = newPopulator();

        List<IndexEntryUpdate<?>> updates = Arrays.asList(
                add( 1, labelSchemaDescriptor, "aaa" ),
                add( 2, labelSchemaDescriptor, "bbb" ),
                add( 3, labelSchemaDescriptor, "ccc" ) );

        updates.forEach( populator::includeSample );

        IndexSample sample = populator.sampleResult();

        assertEquals( new IndexSample( 3, 3, 3 ), sample );
    }

    @Test
    void sampleIncludedUpdatesWithDuplicates() throws Exception
    {
        populator = newPopulator();

        List<IndexEntryUpdate<?>> updates = Arrays.asList(
                add( 1, labelSchemaDescriptor, "foo" ),
                add( 2, labelSchemaDescriptor, "bar" ),
                add( 3, labelSchemaDescriptor, "foo" ) );

        updates.forEach( populator::includeSample );

        IndexSample sample = populator.sampleResult();

        assertEquals( new IndexSample( 3, 2, 3 ), sample );
    }

    @Test
    void addUpdates() throws Exception
    {
        populator = newPopulator();

        List<IndexEntryUpdate<?>> updates = Arrays.asList(
                add( 1, labelSchemaDescriptor, "foo" ),
                add( 2, labelSchemaDescriptor, "bar" ),
                add( 42, labelSchemaDescriptor, "bar" ) );

        populator.add( updates );

        index.maybeRefreshBlocking();
        try ( IndexReader reader = index.getIndexReader();
              NodeValueIterator allEntities = new NodeValueIterator() )
        {
            int propertyKeyId = labelSchemaDescriptor.getPropertyId();
            reader.query( NULL_CONTEXT, allEntities, IndexOrder.NONE, false, IndexQuery.exists( propertyKeyId ) );
            assertArrayEquals( new long[]{1, 2, 42}, PrimitiveLongCollections.asArray( allEntities ) );
        }
    }

    private NonUniqueLuceneIndexPopulator newPopulator() throws IOException
    {
        IndexSamplingConfig samplingConfig = new IndexSamplingConfig( Config.defaults() );
        NonUniqueLuceneIndexPopulator populator = new NonUniqueLuceneIndexPopulator( index, samplingConfig );
        populator.create();
        return populator;
    }
}
