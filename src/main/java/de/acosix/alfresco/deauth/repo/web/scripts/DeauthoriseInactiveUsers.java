/*
 * Copyright 2017 Acosix GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.acosix.alfresco.deauth.repo.web.scripts;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.alfresco.enterprise.repo.authorization.AuthorizationService;
import org.alfresco.repo.batch.BatchProcessWorkProvider;
import org.alfresco.repo.batch.BatchProcessor;
import org.alfresco.repo.batch.BatchProcessor.BatchProcessWorkerAdaptor;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.service.cmr.security.AuthorityService;
import org.alfresco.util.PropertyCheck;
import org.apache.commons.logging.LogFactory;
import org.springframework.extensions.webscripts.Cache;
import org.springframework.extensions.webscripts.Status;
import org.springframework.extensions.webscripts.WebScriptRequest;

import de.acosix.alfresco.audit.repo.web.scripts.AuditUserGet;
import de.acosix.alfresco.audit.repo.web.scripts.AuditUserGet.AuditUserInfo.AuthorisedState;

/**
 * This web script deauthorises any user that has been inactive in a specific timeframe into the past (based on data of an audit application
 * within Alfresco) and that has not been deauthorised before. Only users that currently exist as person nodes in the system will be
 * considered for deauthorisation.
 *
 * @author Axel Faust, <a href="http://acosix.de">Acosix GmbH</a>
 */
public class DeauthoriseInactiveUsers extends AuditUserGet
{

    {
        super.setQueryActiveUsers(false);
    }

    protected AuthorityService authorityService;

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public void afterPropertiesSet()
    {
        super.afterPropertiesSet();
        PropertyCheck.mandatory(this, "authorityService", this.authorityService);
        PropertyCheck.mandatory(this, "authorisationService", this.authorisationService);
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public void setQueryActiveUsers(final boolean queryActiveUsers)
    {
        throw new UnsupportedOperationException(
                "queryActiveUsers cannot be modified - " + this.getClass().getName() + " always queries inactive users");
    }

    /**
     * @param authorityService
     *            the authorityService to set
     */
    public void setAuthorityService(final AuthorityService authorityService)
    {
        this.authorityService = authorityService;
    }

    /**
     * @param authorisationService
     *            the authorisationService to set
     */
    public void setAuthorisationService(final AuthorizationService authorisationService)
    {
        this.setAuthorisationService((Object) authorisationService);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Map<String, Object> executeImpl(final WebScriptRequest req, final Status status, final Cache cache)
    {
        final Map<String, Object> model = super.executeImpl(req, status, cache);
        final Object users = model.get("users");

        // some validation of our expectations
        if (!(users instanceof List<?>))
        {
            throw new IllegalStateException("Model of base class web script contains an unexpected value for \"users\"");
        }

        ((List<?>) users).forEach(userEntry -> {
            if (!(userEntry instanceof Map<?, ?>))
            {
                throw new IllegalStateException("Model of base class web script contains an unexpected value as element of \"users\"");
            }
            final Object info = ((Map<?, ?>) userEntry).get("info");
            if (!(info instanceof AuditUserInfo))
            {
                throw new IllegalStateException(
                        "Model of base class web script contains an unexpected value as \"info\" on element of \"users\"");
            }
        });

        @SuppressWarnings("unchecked")
        final List<Map<Object, Object>> userEntries = ((List<Map<Object, Object>>) users);

        // can use the current transaction for the "before" count
        model.put("authorisedUsersBefore", Long.valueOf(((AuthorizationService) this.authorisationService).getAuthorizedUsersCount()));

        // though deauthorising a user should be a simple operation and not require changes affecting nodes, we still do it in batches
        final PersonDeauthorisationWorker personDeauthorisationWorker = new PersonDeauthorisationWorker();
        // needs to run single-threaded to try and avoid issues with authorisationService
        // (observed quite a lot of update conflicts and inconsistent state in multi-threaded updates)
        final BatchProcessor<Map<Object, Object>> processor = new BatchProcessor<>("DeauthoriseInactiveUsers",
                this.transactionService.getRetryingTransactionHelper(), new PersonDeauthorisationWorkProvider(userEntries), 1,
                this.batchSize, null, LogFactory.getLog(DeauthoriseInactiveUsers.class), this.loggingInterval);
        processor.process(personDeauthorisationWorker, true);

        model.put("deauthorised", Integer.valueOf(personDeauthorisationWorker.getDeauthorised()));
        // need nested transaction for an "after" count
        this.transactionService.getRetryingTransactionHelper().doInTransaction(() -> {
            model.put("authorisedUsersAfter", Long.valueOf(((AuthorizationService) this.authorisationService).getAuthorizedUsersCount()));
            return null;
        }, true, true);

        return model;
    }

    protected static class PersonDeauthorisationWorkProvider implements BatchProcessWorkProvider<Map<Object, Object>>
    {

        private final List<Map<Object, Object>> userEntries;

        protected PersonDeauthorisationWorkProvider(final List<Map<Object, Object>> userEntries)
        {
            this.userEntries = userEntries;
        }

        /**
         *
         * {@inheritDoc}
         */
        @Override
        public int getTotalEstimatedWorkSize()
        {
            final long count = this.userEntries.stream().filter(entry -> {
                final AuditUserInfo info = (AuditUserInfo) entry.get("info");
                final boolean wasAuthorised = info.getAuthorisedState() == AuthorisedState.AUTHORISED;
                return wasAuthorised;
            }).count();
            return (int) count;
        }

        /**
         *
         * {@inheritDoc}
         */
        @Override
        public Collection<Map<Object, Object>> getNextWork()
        {
            final Collection<Map<Object, Object>> work = new ArrayList<>();
            this.userEntries.stream().filter(entry -> {
                final AuditUserInfo info = (AuditUserInfo) entry.get("info");
                final boolean wasAuthorised = info.getAuthorisedState() == AuthorisedState.AUTHORISED;
                return wasAuthorised;
            }).forEach(entry -> {
                work.add(entry);
            });
            return work;
        }

    }

    protected class PersonDeauthorisationWorker extends BatchProcessWorkerAdaptor<Map<Object, Object>>
    {

        private final String runAsUser = AuthenticationUtil.getRunAsUser();

        // use separate counters for total and txn local - txn may be rolled back after all
        private final ThreadLocal<AtomicInteger> deauthorisedTxn = new ThreadLocal<AtomicInteger>()
        {

            /**
             *
             * {@inheritDoc}
             */
            @Override
            protected AtomicInteger initialValue()
            {
                return new AtomicInteger(0);
            }
        };

        private final AtomicInteger deauthorised = new AtomicInteger(0);

        /**
         * {@inheritDoc}
         */
        @Override
        public void beforeProcess() throws Throwable
        {
            AuthenticationUtil.setRunAsUser(this.runAsUser);
            this.deauthorisedTxn.remove();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String getIdentifier(final Map<Object, Object> entry)
        {
            final String userName = ((AuditUserInfo) entry.get("info")).getUserName();
            return userName;
        }

        protected int getDeauthorised()
        {
            return this.deauthorised.intValue();
        }

        /**
         *
         * {@inheritDoc}
         */
        @Override
        public void process(final Map<Object, Object> entry) throws Throwable
        {
            final String userName = ((AuditUserInfo) entry.get("info")).getUserName();

            final boolean wasAuthorised = ((AuthorizationService) DeauthoriseInactiveUsers.this.authorisationService)
                    .isAuthorized(userName);
            final boolean deauthorised;
            if (!(DeauthoriseInactiveUsers.this.authorityService.isAdminAuthority(userName)
                    || DeauthoriseInactiveUsers.this.authorityService.isGuestAuthority(userName)))
            {
                if (wasAuthorised)
                {
                    ((AuthorizationService) DeauthoriseInactiveUsers.this.authorisationService).deauthorize(userName);
                    this.deauthorisedTxn.get().incrementAndGet();
                }
                deauthorised = wasAuthorised;
            }
            else
            {
                deauthorised = false;
            }
            entry.put("wasAuthorised", Boolean.valueOf(wasAuthorised));
            entry.put("deauthorised", Boolean.valueOf(deauthorised));
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void afterProcess() throws Throwable
        {
            this.deauthorised.addAndGet(this.deauthorisedTxn.get().intValue());
        }

    }
}
