package com.example.pep.sdk.starter;

import com.example.pep.sdk.core.annotation.PapEntity;
import com.example.pep.sdk.core.exception.PapSdkException;
import com.example.pep.sdk.core.model.Operation;
import com.example.pep.sdk.core.model.PapEntityChange;
import com.example.pep.sdk.core.model.PapEntityDescriptor;
import com.example.pep.sdk.core.registry.PapEntityRegistry;
import com.example.pep.sdk.sync.ChangeBuffer;
import com.example.pep.sdk.sync.PapTransactionSynchronization;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class PapEntityListener
        implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {

    private static final Logger log = LoggerFactory.getLogger(PapEntityListener.class);

    private final PapEntityRegistry registry;
    private final PapTransactionSynchronization synchronization;

    public PapEntityListener(PapEntityRegistry registry,
                             PapTransactionSynchronization synchronization) {
        this.registry = registry;
        this.synchronization = synchronization;
    }

    @Override public void onPostInsert(PostInsertEvent e)  { capture(e.getEntity(), Operation.CREATE); }
    @Override public void onPostUpdate(PostUpdateEvent e)  { capture(e.getEntity(), Operation.UPDATE); }
    @Override public void onPostDelete(PostDeleteEvent e)  { capture(e.getEntity(), Operation.DELETE); }
    @Override public boolean requiresPostCommitHandling(EntityPersister p) { return false; }

    private void capture(Object entity, Operation op) {
        Class<?> cls = entity.getClass();
        if (cls.getAnnotation(PapEntity.class) == null) return;

        PapEntityDescriptor descriptor = registry.find(cls).orElse(null);
        if (descriptor == null) {
            log.warn("@PapEntity on {} but no descriptor registered; skipping", cls.getName());
            return;
        }

        String id = descriptor.readId(entity);
        if (id == null) {
            log.warn("Entity {} has null id; skipping capture", cls.getName());
            return;
        }

        PapEntityChange change = new PapEntityChange(
                cls, id, op, descriptor.modeFor(op), descriptor.resolveSources(entity));

        getOrCreateBuffer().append(change, descriptor);
    }

    private ChangeBuffer getOrCreateBuffer() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new PapSdkException(
                    "PEP SDK requires an active Spring transaction; wrap the calling code in @Transactional");
        }
        ChangeBuffer existing = (ChangeBuffer)
                TransactionSynchronizationManager.getResource(PapTransactionSynchronization.BUFFER_KEY);
        if (existing != null) return existing;

        ChangeBuffer fresh = new ChangeBuffer();
        TransactionSynchronizationManager.bindResource(PapTransactionSynchronization.BUFFER_KEY, fresh);
        TransactionSynchronizationManager.registerSynchronization(synchronization);
        return fresh;
    }
}
