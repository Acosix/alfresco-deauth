/*
 * Copyright 2017, 2018 Acosix GmbH
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.alfresco.enterprise.repo.authorization.AuthorizationService;
import org.alfresco.repo.batch.BatchProcessor;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.service.cmr.security.AuthorityService;
import org.alfresco.util.PropertyCheck;
import org.apache.commons.logging.LogFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.extensions.webscripts.Cache;
import org.springframework.extensions.webscripts.Status;
import org.springframework.extensions.webscripts.WebScriptRequest;

import de.acosix.alfresco.audit.repo.batch.AuditUserInfo;
import de.acosix.alfresco.audit.repo.batch.AuditUserInfo.AuthorisedState;
import de.acosix.alfresco.audit.repo.batch.PersonAuditWorker;
import de.acosix.alfresco.audit.repo.batch.PersonAuditWorker.PersonAuditQueryMode;
import de.acosix.alfresco.audit.repo.web.scripts.AbstractAuditUserWebScript;
import de.acosix.alfresco.deauth.repo.batch.DeauthorisationUserInfo;
import de.acosix.alfresco.deauth.repo.batch.PersonDeauthorisationWorker;
import de.acosix.alfresco.utility.repo.batch.CollectionWrappingWorkProvider;

/**
 * This web script deauthorises any user that has been inactive in a specific timeframe into the past (based on data of an audit application
 * within Alfresco) and that has not been deauthorised before. Only users that currently exist as person nodes in the system will be
 * considered for deauthorisation.
 *
 * @author Axel Faust
 */
public class DeauthoriseInactiveUsersPost extends AbstractAuditUserWebScript
{

    protected static class DeauthoriseInactiveUsersParameters extends AuditUserWebScriptParameters
    {

        private boolean dryRun = false;

        /**
         * @return the dryRun
         */
        public boolean isDryRun()
        {
            return this.dryRun;
        }

        /**
         * @param dryRun
         *            the dryRun to set
         */
        public void setDryRun(final boolean dryRun)
        {
            this.dryRun = dryRun;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String toString()
        {
            final StringBuilder builder = new StringBuilder();
            builder.append("DeauthoriseInactiveUsersParameters [dryRun=");
            builder.append(this.dryRun);
            builder.append(", ");
            if (this.getLookBackMode() != null)
            {
                builder.append("getLookBackMode()=");
                builder.append(this.getLookBackMode());
                builder.append(", ");
            }
            builder.append("lookBackAmount=");
            builder.append(this.getLookBackAmount());
            builder.append(", fromTime=");
            builder.append(this.getFromTime());
            builder.append(", workerThreads=");
            builder.append(this.getWorkerThreads());
            builder.append(", batchSize=");
            builder.append(this.getBatchSize());
            builder.append("]");
            return builder.toString();
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(DeauthoriseInactiveUsersPost.class);

    protected AuthorityService authorityService;

    protected AuthorizationService authorisationService;

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
        this.authorisationService = authorisationService;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected Map<String, Object> executeImpl(final WebScriptRequest req, final Status status, final Cache cache)
    {
        Map<String, Object> model = super.executeImpl(req, status, cache);
        if (model == null)
        {
            model = new HashMap<>();
        }

        final DeauthoriseInactiveUsersParameters parameters = this.parseRequest(() -> {
            final DeauthoriseInactiveUsersParameters params = new DeauthoriseInactiveUsersParameters();

            final String dryRunParam = req.getParameter("dryRun");
            final boolean dryRun = Boolean.parseBoolean(dryRunParam);
            params.setDryRun(dryRun);

            return params;
        }, req);

        final List<AuditUserInfo> auditUsers = this.queryAuditUsers(PersonAuditQueryMode.INACTIVE_ONLY, parameters);

        LOGGER.debug("Query for inactive users using {} yielded {} results", "inactive", parameters, auditUsers.size());

        final List<DeauthorisationUserInfo> work = new ArrayList<>();

        final List<Object> modelUsers = new ArrayList<>();
        model.put("users", modelUsers);
        auditUsers.forEach(userInfo -> {
            if (userInfo.getAuthorisedState() == AuthorisedState.AUTHORISED)
            {
                final DeauthorisationUserInfo workInfo = new DeauthorisationUserInfo(userInfo);
                work.add(workInfo);

                final Map<String, Object> modelInactiveUser = new HashMap<>();
                modelInactiveUser.put("info", workInfo);
                modelInactiveUser.put("node", userInfo.getPersonRef());
                modelUsers.add(modelInactiveUser);
            }
        });
        LOGGER.debug("Filtered inactive users to {} which are currently authorised", work.size());
        // can use the current transaction for the "before" count
        final long authorizedUsersCount = this.authorisationService.getAuthorizedUsersCount();
        model.put("authorisedUsersBefore", Long.valueOf(authorizedUsersCount));

        final int deauthorised = this.runDeauthorisation(work, parameters);

        LOGGER.debug("Deauthorised {} inactive users", deauthorised);
        LOGGER.trace("User details after processing: {}", work);

        model.put("deauthorised", Integer.valueOf(deauthorised));
        if (!parameters.isDryRun())
        {
            // need nested transaction for an "after" count
            final Long authorizedUsersCount2 = this.transactionService.getRetryingTransactionHelper().doInTransaction(() -> {
                final long authorizedUsersCount3 = this.authorisationService.getAuthorizedUsersCount();
                return Long.valueOf(authorizedUsersCount3);
            }, true, true);
            model.put("authorisedUsersAfter", authorizedUsersCount2);
        }
        else
        {
            model.put("authorisedUsersAfter", Long.valueOf(authorizedUsersCount - deauthorised));
        }

        return model;
    }

    protected <T extends DeauthoriseInactiveUsersParameters> int runDeauthorisation(final Collection<DeauthorisationUserInfo> work,
            final T parameters)
    {
        // can run as system as web script requires admin authentication
        // improves performance and may avoid overwhelming readersCache, readersDeniedCache and others
        return AuthenticationUtil.runAsSystem(() -> {
            // though deauthorising a user should be a simple operation and not require changes affecting nodes, we still do it in batches
            final PersonDeauthorisationWorker personDeauthorisationWorker = new PersonDeauthorisationWorker(parameters.isDryRun(),
                    this.authorityService, this.authorisationService);

            if (!work.isEmpty())
            {
                // needs to run single-threaded to try and avoid issues with authorisationService
                // (observed quite a lot of update conflicts and inconsistent state in multi-threaded updates)
                final BatchProcessor<DeauthorisationUserInfo> processor = new BatchProcessor<>("DeauthoriseInactiveUsers",
                        this.transactionService.getRetryingTransactionHelper(), new CollectionWrappingWorkProvider<>(work, 1), 1,
                        parameters.getBatchSize(), null, LogFactory.getLog(this.getClass().getName() + ".batchProcessor"),
                        this.loggingInterval);
                processor.process(personDeauthorisationWorker, true);
            }

            final int deauthorised = personDeauthorisationWorker.getDeauthorised();
            return deauthorised;
        });
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    protected <T extends AuditUserWebScriptParameters> PersonAuditWorker createBatchWorker(final PersonAuditQueryMode mode,
            final T parameters)
    {
        final PersonAuditWorker personAuditWorker = super.createBatchWorker(mode, parameters);

        personAuditWorker.setIsAuthorisedCheck(userName -> {
            return this.authorisationService.isAuthorized(userName);
        });
        personAuditWorker.setIsDeauthorisedCheck(userName -> {
            return this.authorisationService.isDeauthorized(userName);
        });

        return personAuditWorker;
    }
}
