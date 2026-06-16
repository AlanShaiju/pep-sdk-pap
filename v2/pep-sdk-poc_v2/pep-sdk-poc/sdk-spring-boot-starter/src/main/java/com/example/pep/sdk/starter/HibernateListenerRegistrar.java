package com.example.pep.sdk.starter;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.boot.spi.SessionFactoryOptions;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HibernateListenerRegistrar {

    private static final Logger log = LoggerFactory.getLogger(HibernateListenerRegistrar.class);

    private final EntityManagerFactory entityManagerFactory;
    private final PapEntityListener listener;

    public HibernateListenerRegistrar(EntityManagerFactory entityManagerFactory, PapEntityListener listener) {
        this.entityManagerFactory = entityManagerFactory;
        this.listener = listener;
    }

    @PostConstruct
    public void register() {
        SessionFactoryImplementor sfi = entityManagerFactory.unwrap(SessionFactoryImplementor.class);
        EventListenerRegistry registry = sfi.getServiceRegistry().getService(EventListenerRegistry.class);
        registry.appendListeners(EventType.POST_INSERT, listener);
        registry.appendListeners(EventType.POST_UPDATE, listener);
        registry.appendListeners(EventType.POST_DELETE, listener);
        log.info("PEP SDK : registered Hibernate event listener");

        warnIfOrderingContractAtRisk(sfi);
    }

    /**
     * The SDK's dispatch-order guarantee (PAP call order matches @Transactional call order,
     * for entities of the same operation type — see design doc §8.2) relies on Hibernate NOT
     * regrouping its flush action queues by table. hibernate.order_inserts / order_updates do
     * exactly that regrouping, for JDBC batching performance, and would silently break the
     * ordering contract if enabled alongside SYNC-mode entities. Warn loudly at startup rather
     * than let this surface as a confusing support ticket later.
     */
    private void warnIfOrderingContractAtRisk(SessionFactoryImplementor sfi) {
        SessionFactoryOptions options = sfi.getSessionFactoryOptions();
        boolean ordersInserts = options.isOrderInsertsEnabled();
        boolean ordersUpdates = options.isOrderUpdatesEnabled();
        if (ordersInserts || ordersUpdates) {
            log.warn("PEP SDK : hibernate.order_inserts={} hibernate.order_updates={} — with either "
                    + "enabled, Hibernate regroups flush actions by table for JDBC batching, which "
                    + "breaks the SDK's documented dispatch-order guarantee for SYNC-mode entities "
                    + "(PAP call order will no longer match @Transactional call order). Disable these "
                    + "settings if dispatch order matters, or confirm the affected entities don't rely "
                    + "on cross-entity ordering.",
                    ordersInserts, ordersUpdates);
        }
    }
}
