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
package org.neo4j.recovery;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.internal.kernel.api.Kernel;
import org.neo4j.internal.recordstorage.RecordStorageEngine;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.kernel.impl.store.MetaDataStore;
import org.neo4j.kernel.impl.store.NeoStores;
import org.neo4j.kernel.impl.transaction.log.checkpoint.CheckPointer;
import org.neo4j.kernel.impl.transaction.log.checkpoint.SimpleTriggerInfo;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.test.TestDatabaseManagementServiceBuilder;
import org.neo4j.test.rule.fs.EphemeralFileSystemRule;

import static org.junit.Assert.assertEquals;
import static org.neo4j.configuration.GraphDatabaseSettings.DEFAULT_DATABASE_NAME;
import static org.neo4j.graphdb.Label.label;
import static org.neo4j.internal.kernel.api.TokenRead.NO_TOKEN;
import static org.neo4j.internal.kernel.api.Transaction.Type.explicit;
import static org.neo4j.internal.kernel.api.security.LoginContext.AUTH_DISABLED;

public class CountsStoreRecoveryTest
{
    @Rule
    public final EphemeralFileSystemRule fsRule = new EphemeralFileSystemRule();
    private GraphDatabaseService db;
    private DatabaseManagementService managementService;

    @Before
    public void before()
    {
        managementService = databaseFactory( fsRule.get() ).impermanent().build();
        db = managementService.database( DEFAULT_DATABASE_NAME );
    }

    @After
    public void after()
    {
        managementService.shutdown();
    }

    @Test
    public void shouldRecoverTheCountsStoreEvenWhenIfNeoStoreDoesNotNeedRecovery() throws Exception
    {
        // given
        createNode( "A" );
        checkPoint();
        createNode( "B" );
        flushNeoStoreOnly();

        // when
        crashAndRestart();

        // then
        Kernel kernel = ((GraphDatabaseAPI) db).getDependencyResolver().resolveDependency( Kernel.class );
        try ( org.neo4j.internal.kernel.api.Transaction tx = kernel.beginTransaction( explicit, AUTH_DISABLED ) )
        {
            assertEquals( 1, tx.dataRead().countsForNode( tx.tokenRead().nodeLabel( "A" ) ) );
            assertEquals( 1, tx.dataRead().countsForNode( tx.tokenRead().nodeLabel( "B" ) ) );
            assertEquals( 2, tx.dataRead().countsForNode( NO_TOKEN ) );
        }
    }

    private void flushNeoStoreOnly()
    {
        NeoStores neoStores = ((GraphDatabaseAPI) db).getDependencyResolver()
                .resolveDependency( RecordStorageEngine.class ).testAccessNeoStores();
        MetaDataStore metaDataStore = neoStores.getMetaDataStore();
        metaDataStore.flush();
    }

    private void checkPoint() throws IOException
    {
        ((GraphDatabaseAPI) db).getDependencyResolver()
                               .resolveDependency( CheckPointer.class ).forceCheckPoint(
                new SimpleTriggerInfo( "test" )
        );
    }

    private void crashAndRestart() throws Exception
    {
        final GraphDatabaseService db1 = db;
        FileSystemAbstraction uncleanFs = fsRule.snapshot( managementService::shutdown );
        managementService = databaseFactory( uncleanFs ).impermanent().build();
        db = managementService.database( DEFAULT_DATABASE_NAME );
    }

    private void createNode( String label )
    {
        try ( Transaction tx = db.beginTx() )
        {
            db.createNode( label( label ) );

            tx.success();
        }
    }

    private static TestDatabaseManagementServiceBuilder databaseFactory( FileSystemAbstraction fs )
    {
        return new TestDatabaseManagementServiceBuilder().setFileSystem( fs );
    }
}
