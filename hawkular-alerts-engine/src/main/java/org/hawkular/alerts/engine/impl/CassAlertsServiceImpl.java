/*
 * Copyright 2015 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hawkular.alerts.engine.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.ejb.EJB;
import javax.ejb.Stateless;

import org.hawkular.alerts.api.model.condition.Alert;
import org.hawkular.alerts.api.model.condition.ConditionEval;
import org.hawkular.alerts.api.model.data.Data;
import org.hawkular.alerts.api.model.trigger.Tag;
import org.hawkular.alerts.api.services.AlertsCriteria;
import org.hawkular.alerts.api.services.AlertsService;
import org.hawkular.alerts.engine.log.MsgLogger;
import org.hawkular.alerts.engine.service.AlertsEngine;
import org.jboss.logging.Logger;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.google.common.util.concurrent.Futures;
import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Cassandra implementation for {@link org.hawkular.alerts.api.services.AlertsService}.
 *
 * @author Jay Shaughnessy
 * @author Lucas Ponce
 */
@Stateless
public class CassAlertsServiceImpl implements AlertsService {

    private final MsgLogger msgLog = MsgLogger.LOGGER;
    private final Logger log = Logger.getLogger(CassAlertsServiceImpl.class);

    private Session session;

    private Gson gson;
    private Gson gsonThin;

    @EJB
    AlertsEngine alertsEngine;

    public CassAlertsServiceImpl() {
    }

    @PostConstruct
    public void initServices() {
        try {
            if (session == null) {
                session = CassCluster.getSession();
            }

            GsonBuilder gsonBuilder = new GsonBuilder();
            gsonBuilder.registerTypeHierarchyAdapter(ConditionEval.class, new GsonAdapter<ConditionEval>());
            gson = gsonBuilder.create();

            GsonBuilder gsonBuilderThin = new GsonBuilder();
            gsonBuilderThin.registerTypeHierarchyAdapter(ConditionEval.class, new GsonAdapter<ConditionEval>());
            gsonBuilderThin.addDeserializationExclusionStrategy(new ExclusionStrategy() {
                @Override
                public boolean shouldSkipField(FieldAttributes f) {
                    final Alert.Thin thin = f.getAnnotation(Alert.Thin.class);
                    return thin != null;
                }
                @Override
                public boolean shouldSkipClass(Class<?> clazz) {
                    return false;
                }
            });
            gsonThin = gsonBuilderThin.create();

        } catch (Throwable t) {
            if (log.isDebugEnabled()) {
                t.printStackTrace();
            }
            msgLog.errorCannotInitializeAlertsService(t.getMessage());
        }
    }

    @Override
    public void addAlerts(Collection<Alert> alerts) throws Exception {
        if (alerts == null) {
            throw new IllegalArgumentException("Alerts must be not null");
        }
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        PreparedStatement insertAlert = CassStatement.get(session, CassStatement.INSERT_ALERT);
        PreparedStatement insertAlertTrigger = CassStatement.get(session, CassStatement.INSERT_ALERT_TRIGGER);
        PreparedStatement insertAlertCtime = CassStatement.get(session, CassStatement.INSERT_ALERT_CTIME);
        PreparedStatement insertAlertStatus = CassStatement.get(session, CassStatement.INSERT_ALERT_STATUS);
        try {
            List<ResultSetFuture> futures = new ArrayList<>();
            alerts.stream()
                    .forEach(a -> {
                        futures.add(session.executeAsync(insertAlert.bind(a.getTenantId(), a.getAlertId(),
                                toJson(a))));
                        futures.add(session.executeAsync(insertAlertTrigger.bind(a.getTenantId(),
                                a.getAlertId(),
                                a.getTriggerId())));
                        futures.add(session.executeAsync(insertAlertCtime.bind(a.getTenantId(),
                                a.getAlertId(), a.getCtime())));
                        futures.add(session.executeAsync(insertAlertStatus.bind(a.getTenantId(),
                                a.getAlertId(),
                                a.getStatus().name())));
                    });
            /*
                main method is synchronous so we need to wait until futures are completed
             */
            Futures.allAsList(futures).get();
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
    }

    @Override
    public List<Alert> getAlerts(String tenantId, AlertsCriteria criteria) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        boolean filter = (null != criteria && criteria.hasCriteria());
        boolean thin = (null != criteria && criteria.isThin());

        if (filter) {
            log.debugf("getAlerts criteria: %s", criteria.toString());
        }

        List<Alert> alerts = new ArrayList<>();
        Set<String> alertIds = new HashSet<>();

        try {
            if (filter) {
                /*
                    Get alertIds filtered by triggerIds clause
                 */
                Set<String> alertIdsFilteredByTriggers = new HashSet<>();
                boolean filterByTriggers = filterByTriggers(tenantId, alertIdsFilteredByTriggers, criteria);

                /*
                    Get alertsIds filtered by ctime clause
                 */
                Set<String> alertIdsFilteredByCtime = new HashSet<>();
                boolean filterByCtime = filterByCtime(tenantId, alertIdsFilteredByCtime, criteria);

                /*
                    Get alertsIds filtered by statutes clause
                 */
                Set<String> alertIdsFilteredByStatus = new HashSet<>();
                boolean filterByStatus = filterByStatuses(tenantId, alertIdsFilteredByStatus, criteria);

                /*
                    Get alertsIds explicitly added into the criteria
                 */
                Set<String> alertIdsFilteredByAlerts = new HashSet<>();
                boolean filterByAlerts = filterByAlerts(alertIdsFilteredByAlerts, criteria);

                /*
                    Join of all filters
                 */
                boolean firstJoin = true;
                if (filterByTriggers) {
                    alertIds.addAll(alertIdsFilteredByTriggers);
                    firstJoin = false;
                }
                if (filterByCtime) {
                    if (firstJoin) {
                        alertIds.addAll(alertIdsFilteredByCtime);
                    } else {
                        alertIds.retainAll(alertIdsFilteredByCtime);
                    }
                    firstJoin = false;
                }
                if (filterByStatus) {
                    if (firstJoin) {
                        alertIds.addAll(alertIdsFilteredByStatus);
                    } else {
                        alertIds.retainAll(alertIdsFilteredByStatus);
                    }
                    firstJoin = false;
                }
                if (filterByAlerts) {
                    if (firstJoin) {
                        alertIds.addAll(alertIdsFilteredByAlerts);
                    } else {
                        alertIds.retainAll(alertIdsFilteredByAlerts);
                    }
                }
            }

            if (!filter) {
                /*
                    Get all alerts - Single query
                 */
                PreparedStatement selectAlertsByTenant = CassStatement.get(session,
                        CassStatement.SELECT_ALERTS_BY_TENANT);
                ResultSet rsAlerts = session.execute(selectAlertsByTenant.bind(tenantId));
                for (Row row : rsAlerts) {
                    String payload = row.getString("payload");
                    Alert alert = fromJson(payload, Alert.class, thin);
                    alerts.add(alert);
                }
            } else {
                /*
                    We have a filter, so we are going to perform several queries with alertsIds filtering
                 */
                PreparedStatement selectAlertsByTenantAndAlert = CassStatement.get(session,
                        CassStatement.SELECT_ALERTS_BY_TENANT_AND_ALERT);
                List<ResultSetFuture> futures = alertIds.stream().map(alertId ->
                        session.executeAsync(selectAlertsByTenantAndAlert.bind(tenantId, alertId)))
                        .collect(Collectors.toList());
                List<ResultSet> rsAlerts = Futures.allAsList(futures).get();
                rsAlerts.stream().forEach(r -> {
                    for (Row row : r) {
                        String payload = row.getString("payload");
                        Alert alert = fromJson(payload, Alert.class, thin);
                        alerts.add(alert);
                    }
                });
            }

        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }

        return alerts;
    }

    /*
        Trigger ids can be passed explicitly in the criteria or indirectly via tags.
        This helper method extracts the list of triggers id and populates the set passed as argument.
     */
    private void extractTriggersId(Set<String> triggerIds, AlertsCriteria criteria) throws Exception {
        /*
            Explicit trigger ids
         */
        if (isEmpty(criteria.getTriggerIds())) {
            if (!isEmpty(criteria.getTriggerId())) {
                triggerIds.add(criteria.getTriggerId());
            }
        } else {
            for (String triggerId : criteria.getTriggerIds()) {
                if (isEmpty(triggerId)) {
                    continue;
                }
                triggerIds.add(triggerId);
            }
        }

        /*
            Indirect trigger ids by tags
         */
        if (!isEmpty(criteria.getTags()) || criteria.getTag() != null) {
            Set<Tag> tags = new HashSet<>();
            if (criteria.getTags() != null) {
                tags.addAll(criteria.getTags());
            }
            Tag tag = criteria.getTag();
            if (tag != null) {
                tags.add(tag);
            }
            triggerIds.addAll(getTriggersIdByTags(tags));
        }

    }

    private boolean filterByTriggers(String tenantId, Set<String> alertsId, AlertsCriteria criteria) throws Exception {
        Set<String> triggerIds = new HashSet<>();
        extractTriggersId(triggerIds, criteria);

        boolean filterByTriggers = false;

        if (triggerIds.size() > 0) {
            filterByTriggers = true;
            List<ResultSetFuture> futures = new ArrayList<>();
            PreparedStatement selectAlertsTriggers = CassStatement.get(session, CassStatement.SELECT_ALERTS_TRIGGERS);

            for (String triggerId : triggerIds) {
                if (isEmpty(triggerId)) {
                    continue;
                }
                futures.add(session.executeAsync(selectAlertsTriggers.bind(tenantId, triggerId)));
            }

            List<ResultSet> rsAlertIdsByTriggerIds = Futures.allAsList(futures).get();

            rsAlertIdsByTriggerIds.stream().forEach(r -> {
                for (Row row : r) {
                    String alertId = row.getString("alertId");
                    alertsId.add(alertId);
                }
            });
            /*
                If there is not alertId but we have triggersId means that we have an empty result.
                So we need to sure a alertId to mark that we have an empty result for future joins.
             */
            if (alertsId.isEmpty()) {
                alertsId.add("no-result-fake-alert-id");
            }
        }

        return filterByTriggers;
    }

    private boolean filterByCtime(String tenantId, Set<String> alertsId, AlertsCriteria criteria) throws Exception {
        boolean filterByCtime = false;
        if (criteria.getStartTime() != null || criteria.getEndTime() != null) {
            filterByCtime = true;

            BoundStatement boundCtime;
            if (criteria.getStartTime() != null && criteria.getEndTime() != null) {
                PreparedStatement selectAlertCTimeStartEnd = CassStatement.get(session,
                        CassStatement.SELECT_ALERT_CTIME_START_END);
                boundCtime = selectAlertCTimeStartEnd.bind(tenantId, criteria.getStartTime(),
                        criteria.getEndTime());
            } else if (criteria.getStartTime() != null) {
                PreparedStatement selectAlertCTimeStart = CassStatement.get(session,
                        CassStatement.SELECT_ALERT_CTIME_START);
                boundCtime = selectAlertCTimeStart.bind(tenantId, criteria.getStartTime());
            } else {
                PreparedStatement selectAlertCTimeEnd = CassStatement.get(session,
                        CassStatement.SELECT_ALERT_CTIME_END);
                boundCtime = selectAlertCTimeEnd.bind(tenantId, criteria.getEndTime());
            }

            ResultSet rsAlertsCtimes = session.execute(boundCtime);
            if (rsAlertsCtimes.isExhausted()) {
                alertsId.add("no-result-fake-alert-id");
            } else {
                for (Row row : rsAlertsCtimes) {
                    String alertId = row.getString("alertId");
                    alertsId.add(alertId);
                }
            }
        }
        return filterByCtime;
    }

    private boolean filterByStatuses(String tenantId, Set<String> alertsId, AlertsCriteria criteria) throws Exception {
        boolean filterByStatus = false;
        Set<Alert.Status> statuses = new HashSet<>();
        if (isEmpty(criteria.getStatusSet())) {
            if (criteria.getStatus() != null) {
                statuses.add(criteria.getStatus());
            }
        } else {
            statuses.addAll(criteria.getStatusSet());
        }

        if (statuses.size() > 0) {
            filterByStatus = true;
            PreparedStatement selectAlertStatusByTenantAndStatus = CassStatement.get(session,
                    CassStatement.SELECT_ALERT_STATUS_BY_TENANT_AND_STATUS);
            List<ResultSetFuture> futures = statuses.stream().map(status ->
                    session.executeAsync(selectAlertStatusByTenantAndStatus.bind(tenantId, status.name())))
                    .collect(Collectors.toList());

            List<ResultSet> rsAlertStatuses = Futures.allAsList(futures).get();
            rsAlertStatuses.stream().forEach(r -> {
                for (Row row : r) {
                    String alertId = row.getString("alertId");
                    alertsId.add(alertId);
                }
            });
            /*
                If there is not alertId but we have triggersId means that we have an empty result.
                So we need to sure a alertId to mark that we have an empty result for future joins.
             */
            if (alertsId.isEmpty()) {
                alertsId.add("no-result-fake-alert-id");
            }
        }
        return filterByStatus;
    }

    private boolean filterByAlerts(Set<String> alertsId, AlertsCriteria criteria) {
        boolean filterByAlerts = false;
        if (isEmpty(criteria.getAlertIds())) {
            if (!isEmpty(criteria.getAlertId())) {
                filterByAlerts = true;
                alertsId.add(criteria.getAlertId());
            }
        } else {
            filterByAlerts = true;
            alertsId.addAll(criteria.getAlertIds());
        }
        return filterByAlerts;
    }

    private Collection<String> getTriggersIdByTags(Collection<Tag> tags) throws Exception {
        Set<String> triggerIds = new HashSet<>();
        List<ResultSetFuture> futures = new ArrayList<>();
        PreparedStatement selectTagsTriggersByCategoryAndName = CassStatement.get(session,
                CassStatement.SELECT_TAGS_TRIGGERS_BY_CATEGORY_AND_NAME);
        PreparedStatement selectTagsTriggersByCategory = CassStatement.get(session,
                CassStatement.SELECT_TAGS_TRIGGERS_BY_CATEGORY);
        PreparedStatement selectTagsTriggersByName = CassStatement.get(session,
                CassStatement.SELECT_TAGS_TRIGGERS_BY_NAME);

        for (Tag tag : tags) {
            if (tag.getCategory() != null || tag.getName() != null) {
                BoundStatement boundTag;
                if (!isEmpty(tag.getCategory()) && !isEmpty(tag.getName())) {
                    boundTag = selectTagsTriggersByCategoryAndName.bind(tag.getTenantId(), tag.getCategory(),
                            tag.getName());
                } else if (!isEmpty(tag.getCategory())) {
                    boundTag = selectTagsTriggersByCategory.bind(tag.getTenantId(), tag.getCategory());
                } else {
                    boundTag = selectTagsTriggersByName.bind(tag.getTenantId(), tag.getName());
                }
                futures.add(session.executeAsync(boundTag));
            }
        }
        List<ResultSet> rsTriggers = Futures.allAsList(futures).get();
        rsTriggers.stream().forEach(r -> {
            for (Row row : r) {
                Set<String> triggers = row.getSet("triggers", String.class);
                triggerIds.addAll(triggers);
            }
        });
        return triggerIds;
    }

    @Override
    public void ackAlerts(String tenantId, Collection<String> alertIds, String ackBy, String ackNotes)
            throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(alertIds)) {
            return;
        }

        AlertsCriteria criteria = new AlertsCriteria();
        criteria.setAlertIds(alertIds);
        List<Alert> alertsToAck = getAlerts(tenantId, criteria);

        for (Alert a : alertsToAck) {
            a.setStatus(Alert.Status.ACKNOWLEDGED);
            a.setAckBy(ackBy);
            a.setAckNotes(ackNotes);
            updateAlertStatus(a);
        }
    }

    @Override
    public void resolveAlerts(String tenantId, Collection<String> alertIds, String resolvedBy, String resolvedNotes,
            List<Set<ConditionEval>> resolvedEvalSets) throws Exception {
        if (isEmpty(tenantId)) {
            throw new IllegalArgumentException("TenantId must be not null");
        }
        if (isEmpty(alertIds)) {
            return;
        }

        AlertsCriteria criteria = new AlertsCriteria();
        criteria.setAlertIds(alertIds);
        List<Alert> alertsToResolve = getAlerts(tenantId, criteria);

        for (Alert a : alertsToResolve) {
            a.setStatus(Alert.Status.RESOLVED);
            a.setResolvedBy(resolvedBy);
            a.setResolvedNotes(resolvedNotes);
            a.setResolvedEvalSets(resolvedEvalSets);
            updateAlertStatus(a);
        }
    }

    @Override
    public void resolveAlertsForTrigger(String tenantId, String triggerId, String resolvedBy, String resolvedNotes,
            List<Set<ConditionEval>> resolvedEvalSets) throws Exception {

        if (isEmpty(triggerId)) {
            return;
        }

        AlertsCriteria criteria = new AlertsCriteria();
        criteria.setTriggerId(triggerId);
        criteria.setStatusSet(EnumSet.complementOf(EnumSet.of(Alert.Status.RESOLVED)));
        List<Alert> alertsToResolve = getAlerts(tenantId, criteria);

        for (Alert a : alertsToResolve) {
            a.setStatus(Alert.Status.RESOLVED);
            a.setResolvedBy(resolvedBy);
            a.setResolvedNotes(resolvedNotes);
            a.setResolvedEvalSets(resolvedEvalSets);
            updateAlertStatus(a);
        }
    }

    private Alert updateAlertStatus(Alert alert) throws Exception {
        if (alert == null || alert.getAlertId() == null || alert.getAlertId().isEmpty()) {
            throw new IllegalArgumentException("AlertId must be not null");
        }
        if (session == null) {
            throw new RuntimeException("Cassandra session is null");
        }
        try {
            /*
                Not sure if these queries can be wrapped in an async way as they have dependencies with results.
                Async pattern could bring race hazards here.
             */
            PreparedStatement selectAlertStatus = CassStatement.get(session,
                    CassStatement.SELECT_ALERT_STATUS);
            PreparedStatement insertAlertStatus = CassStatement.get(session,
                    CassStatement.INSERT_ALERT_STATUS);
            PreparedStatement deleteAlertStatus = CassStatement.get(session,
                    CassStatement.DELETE_ALERT_STATUS);
            PreparedStatement updateAlert = CassStatement.get(session,
                    CassStatement.UPDATE_ALERT);

            List<ResultSetFuture> futures = new ArrayList<>();
            futures.add(session.executeAsync(selectAlertStatus.bind(alert.getTenantId(), Alert.Status.OPEN.name(),
                    alert.getAlertId())));
            futures.add(session.executeAsync(selectAlertStatus.bind(alert.getTenantId(),
                    Alert.Status.ACKNOWLEDGED.name(), alert.getAlertId())));
            futures.add(session.executeAsync(selectAlertStatus.bind(alert.getTenantId(), Alert.Status.RESOLVED.name(),
                    alert.getAlertId())));

            List<ResultSet> rsAlertsStatusToDelete = Futures.allAsList(futures).get();
            rsAlertsStatusToDelete.stream().forEach(r -> {
                for (Row row : r) {
                    String alertIdToDelete = row.getString("alertId");
                    String statusToDelete = row.getString("status");
                    session.execute(deleteAlertStatus.bind(alert.getTenantId(), statusToDelete, alertIdToDelete));
                }
            });
            session.execute(insertAlertStatus.bind(alert.getTenantId(), alert.getAlertId(), alert.getStatus().name()));
            session.execute(updateAlert.bind(toJson(alert), alert.getTenantId(), alert.getAlertId()));
        } catch (Exception e) {
            msgLog.errorDatabaseException(e.getMessage());
            throw e;
        }
        return alert;
    }

    @Override
    public void sendData(Data data) throws Exception {
        alertsEngine.sendData(data);
    }

    @Override
    public void sendData(Collection<Data> data) throws Exception {
        alertsEngine.sendData(data);
    }

    private String toJson(Object resource) {

        String result = gson.toJson(resource);
        log.debugf(result);
        return result;
    }

    private <T> T fromJson(String json, Class<T> clazz, boolean thin) {

        return thin ? gsonThin.fromJson(json, clazz) : gson.fromJson(json, clazz);
    }

    private boolean isEmpty(Collection<?> c) {
        return null == c || c.isEmpty();
    }

    private boolean isEmpty(String s) {
        return null == s || s.trim().isEmpty();
    }

}
