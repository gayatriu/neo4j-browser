/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
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
package org.neo4j.kernel.impl.api.index;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.helpers.BiConsumer;
import org.neo4j.helpers.Pair;
import org.neo4j.kernel.api.TokenNameLookup;
import org.neo4j.kernel.api.exceptions.index.IndexActivationFailedKernelException;
import org.neo4j.kernel.api.exceptions.index.IndexNotFoundKernelException;
import org.neo4j.kernel.api.exceptions.index.IndexPopulationFailedKernelException;
import org.neo4j.kernel.api.exceptions.schema.ConstraintVerificationFailedKernelException;
import org.neo4j.kernel.api.index.IndexDescriptor;
import org.neo4j.kernel.api.index.IndexEntryConflictException;
import org.neo4j.kernel.api.index.IndexUpdater;
import org.neo4j.kernel.api.index.InternalIndexState;
import org.neo4j.kernel.api.index.NodePropertyUpdate;
import org.neo4j.kernel.api.index.SchemaIndexProvider;
import org.neo4j.kernel.api.index.SchemaIndexProvider.Descriptor;
import org.neo4j.kernel.impl.api.UpdateableSchemaState;
import org.neo4j.kernel.impl.api.index.sampling.IndexSamplingConfig;
import org.neo4j.kernel.impl.api.index.sampling.IndexSamplingController;
import org.neo4j.kernel.impl.api.index.sampling.IndexSamplingMode;
import org.neo4j.kernel.impl.store.UnderlyingStorageException;
import org.neo4j.kernel.impl.store.record.IndexRule;
import org.neo4j.kernel.impl.util.JobScheduler;
import org.neo4j.kernel.impl.util.StringLogger;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.kernel.logging.Logging;
import org.neo4j.register.Register.DoubleLongRegister;
import org.neo4j.register.Registers;

import static java.util.concurrent.TimeUnit.MINUTES;

import static org.neo4j.helpers.Exceptions.launderedException;
import static org.neo4j.helpers.collection.Iterables.concatResourceIterators;
import static org.neo4j.kernel.impl.api.index.IndexPopulationFailure.failure;

/**
 * Manages the indexes that were introduced in 2.0. These indexes depend on the normal neo4j logical log for
 * transactionality. Each index has an {@link org.neo4j.kernel.impl.store.record.IndexRule}, which it uses to filter
 * changes that come into the database. Changes that apply to the the rule are indexed. This way, "normal" changes to
 * the database can be replayed to perform recovery after a crash.
 * <p/>
 * <h3>Recovery procedure</h3>
 * <p/>
 * Each index has a state, as defined in {@link org.neo4j.kernel.api.index.InternalIndexState}, which is used during
 * recovery. If an index is anything but {@link org.neo4j.kernel.api.index.InternalIndexState#ONLINE}, it will simply be
 * destroyed and re-created.
 * <p/>
 * If, however, it is {@link org.neo4j.kernel.api.index.InternalIndexState#ONLINE}, the index provider is required to
 * also guarantee that the index had been flushed to disk.
 */
public class IndexingService extends LifecycleAdapter implements IndexMapSnapshotProvider
{
    private final IndexSamplingController samplingController;
    private final IndexSamplingSetup samplingSetup;
    private final IndexProxySetup proxySetup;
    private final IndexMapReference indexMapRef = new IndexMapReference();
    private final IndexStoreView storeView;
    private final SchemaIndexProviderMap providerMap;
    private final Iterable<IndexRule> indexRules;
    private final StringLogger logger;
    private final Set<Long> recoveredNodeIds = new HashSet<>();
    private final Monitor monitor;

    enum State
    {
        NOT_STARTED,
        STARTING,
        RUNNING,
        STOPPED
    }

    public interface Monitor
    {
        void applyingRecoveredData( Set<Long> recoveredNodeIds );

        void appliedRecoveredData( Iterable<NodePropertyUpdate> updates );
    }

    public static abstract class MonitorAdapter implements Monitor
    {
        @Override
        public void appliedRecoveredData( Iterable<NodePropertyUpdate> updates )
        {   // Do nothing
        }

        @Override
        public void applyingRecoveredData( Set<Long> recoveredNodeIds )
        {   // Do nothing
        }
    }

    public static final Monitor NO_MONITOR = new MonitorAdapter()
    {
    };

    private volatile State state = State.NOT_STARTED;

    protected IndexingService( IndexSamplingSetup samplingSetup,
                               IndexProxySetup proxySetup,
                               SchemaIndexProviderMap providerMap,
                               IndexStoreView storeView,
                               Iterable<IndexRule> indexRules,
                               Logging logging,
                               Monitor monitor )
    {
        this.storeView = storeView;
        this.samplingSetup = samplingSetup;
        this.proxySetup = proxySetup;
        this.samplingController = samplingSetup.createIndexSamplingController( this );
        this.providerMap = providerMap;
        this.indexRules = indexRules;
        this.monitor = monitor;
        this.logger = logging.getMessagesLog( getClass() );
    }

    public static IndexingService create( IndexSamplingConfig samplingConfig,
                                          JobScheduler scheduler,
                                          SchemaIndexProviderMap providerMap,
                                          IndexStoreView storeView,
                                          TokenNameLookup tokenNameLookup,
                                          UpdateableSchemaState updateableSchemaState,
                                          Iterable<IndexRule> indexRules,
                                          Logging logging, Monitor monitor )
    {
        if ( providerMap == null || providerMap.getDefaultProvider() == null )
        {
            // For now
            throw new IllegalStateException( "You cannot run the database without an index provider, " +
                    "please make sure that a valid provider (subclass of " + SchemaIndexProvider.class.getName() +
                    ") is on your classpath." );
        }

        IndexSamplingSetup samplingSetup = new IndexSamplingSetup( samplingConfig, storeView, scheduler, logging );
        return new IndexingService(
            samplingSetup,
            new IndexProxySetup( samplingSetup, storeView, providerMap, updateableSchemaState, tokenNameLookup, scheduler, logging ),
            providerMap,
            storeView,
            indexRules,
            logging,
            monitor
        );
    }

    /**
         * Called while the database starts up, before recovery.
         */
    @Override
    public void init()
    {
        IndexMap indexMap = indexMapSnapshot();

        for ( IndexRule indexRule : indexRules )
        {
            IndexProxy indexProxy;

            long indexId = indexRule.getId();
            IndexDescriptor descriptor = new IndexDescriptor( indexRule.getLabel(), indexRule.getPropertyKey() );
            SchemaIndexProvider.Descriptor providerDescriptor = indexRule.getProviderDescriptor();
            SchemaIndexProvider provider = providerMap.apply( providerDescriptor );
            InternalIndexState initialState = provider.getInitialState( indexId );
            logger.info( proxySetup.indexStateInfo( "init", indexId, initialState, descriptor ) );
            boolean constraint = indexRule.isConstraintIndex();

            switch ( initialState )
            {
                case ONLINE:
                    indexProxy =
                        proxySetup.createOnlineIndexProxy( indexId, descriptor, providerDescriptor, constraint );
                    break;
                case POPULATING:
                    // The database was shut down during population, or a crash has occurred, or some other sad thing.

                    indexProxy = proxySetup.createRecoveringIndexProxy( descriptor, providerDescriptor, constraint );
                    break;
                case FAILED:
                    IndexPopulationFailure failure = failure( provider.getPopulationFailure( indexId ) );
                    indexProxy = proxySetup.createFailedIndexProxy( indexId, descriptor, providerDescriptor, constraint, failure );
                    break;
                default:
                    throw new IllegalArgumentException( "" + initialState );
            }
            indexMap.putIndexProxy( indexId, indexProxy );
        }

        indexMapRef.setIndexMap( indexMap );
    }

    // Recovery semantics: This is to be called after init, and after the database has run recovery.
    @Override
    public void start() throws IOException
    {
        state = State.STARTING;

        applyRecoveredUpdates();
        IndexMap indexMap = indexMapSnapshot();

        final Map<Long, Pair<IndexDescriptor, SchemaIndexProvider.Descriptor>> rebuildingDescriptors = new HashMap<>();

        // Find all indexes that are not already online, do not require rebuilding, and create them
        indexMap.foreachIndexProxy( new BiConsumer<Long, IndexProxy>()
        {
            @Override
            public void accept( Long indexId, IndexProxy proxy )
            {
                InternalIndexState state = proxy.getState();
                IndexDescriptor descriptor = proxy.getDescriptor();
                logger.info( proxySetup.indexStateInfo( "start", indexId, state, descriptor ) );
                switch ( state )
                {
                    case ONLINE:
                        // Don't do anything, index is ok.
                        break;
                    case POPULATING:
                        // Remember for rebuilding
                        rebuildingDescriptors.put( indexId, Pair.of( descriptor, proxy.getProviderDescriptor() ) );
                        break;
                    case FAILED:
                        // Don't do anything, the user needs to drop the index and re-create
                        break;
                }
            }
        } );

        // Drop placeholder proxies for indexes that need to be rebuilt
        dropRecoveringIndexes( indexMap, rebuildingDescriptors );

        // Rebuild indexes by recreating and repopulating them
        for ( Map.Entry<Long, Pair<IndexDescriptor, SchemaIndexProvider.Descriptor>> entry : rebuildingDescriptors.entrySet() )
        {
            long indexId = entry.getKey();
            Pair<IndexDescriptor, SchemaIndexProvider.Descriptor> descriptors = entry.getValue();
            IndexDescriptor indexDescriptor = descriptors.first();
            SchemaIndexProvider.Descriptor providerDescriptor = descriptors.other();

            /*
             * Passing in "false" for unique here may seem surprising, and.. well, yes, it is, I was surprised too.
             * However, it is actually perfectly safe, because whenever we have constraint indexes here, they will
             * be in a state where they didn't finish populating, and despite the fact that we re-create them here,
             * they will get dropped as soon as recovery is completed by the constraint system.
             */
            IndexProxy proxy = proxySetup.createPopulatingIndexProxy( indexId, indexDescriptor, providerDescriptor, false );
            proxy.start();
            indexMap.putIndexProxy( indexId, proxy );
        }

        indexMapRef.setIndexMap( indexMap );
        samplingSetup.scheduleBackgroundJob( samplingController );
        state = State.RUNNING;
    }

    @Override
    public void stop()
    {
        state = State.STOPPED;
        closeAllIndexes();
    }

    public long indexSize( long indexId ) throws IndexNotFoundKernelException
    {
        final IndexProxy indexProxy = indexMapRef.getOnlineIndexProxy( indexId );
        return storeView.indexSize( indexProxy.getDescriptor() );
    }

    public long indexUpdates( long indexId ) throws IndexNotFoundKernelException
    {
        final IndexProxy indexProxy = indexMapRef.getOnlineIndexProxy( indexId );
        return storeView.indexUpdates( indexProxy.getDescriptor() );
    }

    public double indexUniqueValuesPercentage( long indexId ) throws IndexNotFoundKernelException
    {
        final IndexProxy indexProxy = indexMapRef.getOnlineIndexProxy( indexId );
        final DoubleLongRegister output = Registers.newDoubleLongRegister();
        storeView.indexSample( indexProxy.getDescriptor(), output );
        long unique = output.readFirst();
        long size = output.readSecond();
        if ( size == 0 )
        {
            return 1.0d;
        }
        else
        {
            return ((double) unique) / ((double) size);
        }
    }

    /*
     * Creates an index.
     *
     * This code is called from the transaction infrastructure during transaction commits, which means that
     * it is *vital* that it is stable, and handles errors very well. Failing here means that the entire db
     * will shut down.
     */
    public void createIndex( IndexRule rule )
    {
        IndexMap indexMap = indexMapSnapshot();

        long ruleId = rule.getId();
        IndexProxy index = indexMap.getIndexProxy( ruleId );
        if (index != null)
        {
            // We already have this index
            return;
        }
        final IndexDescriptor descriptor = new IndexDescriptor( rule.getLabel(), rule.getPropertyKey() );
        SchemaIndexProvider.Descriptor providerDescriptor = rule.getProviderDescriptor();
        boolean constraint = rule.isConstraintIndex();
        if ( state == State.RUNNING )
        {
            try
            {
                index = proxySetup.createPopulatingIndexProxy( ruleId, descriptor, providerDescriptor, constraint );
                index.start();
            }
            catch ( IOException e )
            {
                throw new RuntimeException( e );
            }
        }
        else
        {
            index = proxySetup.createRecoveringIndexProxy( descriptor, providerDescriptor, constraint );
        }

        indexMap.putIndexProxy( rule.getId(), index );
        indexMapRef.setIndexMap( indexMap );
    }

    @Override
    public IndexMap indexMapSnapshot()
    {
        return indexMapRef.getIndexMapCopy();
    }

    public void updateIndexes( IndexUpdates updates, long transactionId, boolean forceIdempotency )
    {
        if ( state == State.RUNNING )
        {

            IndexUpdateMode mode = forceIdempotency ? IndexUpdateMode.RECOVERY : IndexUpdateMode.ONLINE;
            try ( IndexUpdaterMap updaterMap = indexMapRef.createIndexUpdaterMap( mode ) )
            {
                applyUpdates( updates, updaterMap );
            }
        }
        else
        {
            if( state == State.NOT_STARTED )
            {
                recoveredNodeIds.addAll( updates.changedNodeIds() );
            }
            else
            {
                // This is a temporary measure to resolve a corruption bug. We believe that it's caused by stray
                // HA transactions, and we know that this measure will fix it. It appears, however, that the correct
                // fix will be, as it is for several other issues, to modify the system to allow us to kill running
                // transactions before state switches.
                throw new IllegalStateException( "Cannot queue index updates while index service is " + state );
            }
        }
    }

    protected void applyRecoveredUpdates() throws IOException
    {
        logger.debug( "Applying recovered updates: " + recoveredNodeIds );
        monitor.applyingRecoveredData( recoveredNodeIds );
        if ( !recoveredNodeIds.isEmpty() )
        {
            try ( IndexUpdaterMap updaterMap = indexMapRef.createIndexUpdaterMap( IndexUpdateMode.RECOVERY ) )
            {
                for ( IndexUpdater updater : updaterMap )
                {
                    updater.remove( recoveredNodeIds );
                }
                for ( long nodeId : recoveredNodeIds )
                {
                    Iterable<NodePropertyUpdate> updates = storeView.nodeAsUpdates( nodeId );
                    applyUpdates( updates, updaterMap );
                    monitor.appliedRecoveredData( updates );
                }
            }
        }
        recoveredNodeIds.clear();
    }

    private void applyUpdates( Iterable<NodePropertyUpdate> updates,  IndexUpdaterMap updaterMap )
    {
        for ( NodePropertyUpdate update : updates )
        {
            int propertyKeyId = update.getPropertyKeyId();
            switch ( update.getUpdateMode() )
            {
            case ADDED:
                for ( int len = update.getNumberOfLabelsAfter(), i = 0; i < len; i++ )
                {
                    IndexDescriptor descriptor = new IndexDescriptor( update.getLabelAfter( i ), propertyKeyId );
                    processUpdateIfIndexExists( updaterMap, update, descriptor );
                }
                break;

            case REMOVED:
                for ( int len = update.getNumberOfLabelsBefore(), i = 0; i < len; i++ )
                {
                    IndexDescriptor descriptor = new IndexDescriptor( update.getLabelBefore( i ), propertyKeyId );
                    processUpdateIfIndexExists( updaterMap, update, descriptor );
                }
                break;

            case CHANGED:
                int lenBefore = update.getNumberOfLabelsBefore();
                int lenAfter = update.getNumberOfLabelsAfter();

                for ( int i = 0, j = 0; i < lenBefore && j < lenAfter; )
                {
                    int labelBefore = update.getLabelBefore( i );
                    int labelAfter = update.getLabelAfter( j );

                    if ( labelBefore == labelAfter )
                    {
                        IndexDescriptor descriptor = new IndexDescriptor( labelAfter, propertyKeyId );
                        processUpdateIfIndexExists( updaterMap, update, descriptor );
                        i++;
                        j++;
                    }
                    else
                    {
                        if ( labelBefore < labelAfter )
                        {
                            i++;
                        }
                        else /* labelBefore > labelAfter */
                        {
                            j++;
                        }
                    }
                }
                break;
            }
        }
    }

    private IndexDescriptor processUpdateIfIndexExists(  IndexUpdaterMap updaterMap, NodePropertyUpdate update, IndexDescriptor descriptor )
    {
        try
        {
            IndexUpdater updater = updaterMap.getUpdater( descriptor );
            if ( null != updater )
            {
                updater.process( update );
                return descriptor;
            }
        }
        catch ( IOException | IndexEntryConflictException e )
        {
            throw new UnderlyingStorageException( e );
        }
        return null;
    }

    public void dropIndex( IndexRule rule )
    {
        long indexId = rule.getId();
        IndexProxy index = indexMapRef.removeIndexProxy( indexId );
        if ( state == State.RUNNING )
        {
            assert index != null : "Index " + rule + " doesn't exists";
            try
            {
                Future<Void> dropFuture = index.drop();
                awaitIndexFuture( dropFuture );
            }
            catch ( Exception e )
            {
                throw launderedException( e );
            }
        }
    }

    public void triggerIndexSampling( IndexSamplingMode mode )
    {
        samplingController.sampleIndexes( mode );
    }

    private void awaitIndexFuture( Future<Void> future ) throws Exception
    {
        try
        {
            future.get( 1, MINUTES );
        }
        catch ( InterruptedException e )
        {
            Thread.interrupted();
            throw e;
        }
    }

    private void dropRecoveringIndexes(
        IndexMap indexMap, Map<Long, Pair<IndexDescriptor,SchemaIndexProvider.Descriptor>> recoveringIndexes )
            throws IOException
    {
        for ( long indexId : recoveringIndexes.keySet() )
        {
            IndexProxy indexProxy = indexMap.removeIndexProxy( indexId );
            indexProxy.drop();
        }
    }

    public void activateIndex( long indexId ) throws
            IndexNotFoundKernelException, IndexActivationFailedKernelException, IndexPopulationFailedKernelException
    {
        try
        {
            if ( state == State.RUNNING ) // don't do this during recovery.
            {
                IndexProxy index = getIndexProxy( indexId );
                index.awaitStoreScanCompleted();
                index.activate();
            }
        }
        catch ( InterruptedException e )
        {
            Thread.interrupted();
            throw new IndexActivationFailedKernelException( e, "Unable to activate index, thread was interrupted." );
        }
    }

    public IndexProxy getIndexProxy( long indexId ) throws IndexNotFoundKernelException
    {
        return indexMapRef.getIndexProxy( indexId );
    }

    public void validateIndex( long indexId ) throws IndexNotFoundKernelException, ConstraintVerificationFailedKernelException, IndexPopulationFailedKernelException
    {
        getIndexProxy( indexId ).validate();
    }

    public void flushAll()
    {
        for ( IndexProxy index : indexMapRef.getAllIndexProxies() )
        {
            try
            {
                index.force();
            }
            catch ( IOException e )
            {
                throw new UnderlyingStorageException( "Unable to force " + index, e );
            }
        }
    }

    private void closeAllIndexes()
    {
        Iterable<IndexProxy> indexesToStop = indexMapRef.clear();
        Collection<Future<Void>> indexStopFutures = new ArrayList<>();
        for ( IndexProxy index : indexesToStop )
        {
            try
            {
                indexStopFutures.add( index.close() );
            }
            catch ( IOException e )
            {
                logger.error( "Unable to close index", e );
            }
        }

        for ( Future<Void> future : indexStopFutures )
        {
            try
            {
                awaitIndexFuture( future );
            }
            catch ( Exception e )
            {
                logger.error( "Error awaiting index to close", e );
            }
        }
    }

    public ResourceIterator<File> snapshotStoreFiles() throws IOException
    {
        Collection<ResourceIterator<File>> snapshots = new ArrayList<>();
        Set<SchemaIndexProvider.Descriptor> fromProviders = new HashSet<>();
        for ( IndexProxy indexProxy : indexMapRef.getAllIndexProxies() )
        {
            Descriptor providerDescriptor = indexProxy.getProviderDescriptor();
            if ( fromProviders.add( providerDescriptor ) )
            {
                snapshots.add( providerMap.apply( providerDescriptor ).snapshotMetaFiles() );
            }
            snapshots.add( indexProxy.snapshotFiles() );
        }

        return concatResourceIterators( snapshots.iterator() );
    }
}
