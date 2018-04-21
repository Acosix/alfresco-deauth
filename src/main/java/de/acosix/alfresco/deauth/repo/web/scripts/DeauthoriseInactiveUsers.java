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
import java.util.List;
import java.util.Map;

import org.alfresco.enterprise.repo.authorization.AuthorizationService;
import org.alfresco.repo.batch.BatchProcessor;
import org.alfresco.service.cmr.security.AuthorityService;
import org.alfresco.util.PropertyCheck;
import org.apache.commons.logging.LogFactory;
import org.springframework.extensions.webscripts.Cache;
import org.springframework.extensions.webscripts.Status;
import org.springframework.extensions.webscripts.WebScriptRequest;

import de.acosix.alfresco.audit.repo.batch.AuditUserInfo;
import de.acosix.alfresco.audit.repo.batch.AuditUserInfo.AuthorisedState;
import de.acosix.alfresco.audit.repo.web.scripts.AuditUserGet;
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
        final String dryRunParam = req.getParameter("dryRun");
        final boolean dryRun = Boolean.parseBoolean(dryRunParam);

        final Map<String, Object> model = super.executeImpl(req, status, cache);
        final Object users = model.get("users");

        // some validation of our expectations
        if (!(users instanceof List<?>))
        {
            throw new IllegalStateException("Model of base class web script contains an unexpected value for \"users\"");
        }

        final List<DeauthorisationUserInfo> work = new ArrayList<>();
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

            final DeauthorisationUserInfo workInfo = new DeauthorisationUserInfo((AuditUserInfo) info);
            if (workInfo.getAuditUserInfo().getAuthorisedState() == AuthorisedState.AUTHORISED)
            {
                work.add(workInfo);
            }

            @SuppressWarnings("unchecked")
            final Map<Object, Object> userModel = (Map<Object, Object>) userEntry;
            userModel.put("info", workInfo);
        });

        // can use the current transaction for the "before" count
        final long authorizedUsersCount = ((AuthorizationService) this.authorisationService).getAuthorizedUsersCount();
        model.put("authorisedUsersBefore", Long.valueOf(authorizedUsersCount));

        // though deauthorising a user should be a simple operation and not require changes affecting nodes, we still do it in batches
        final PersonDeauthorisationWorker personDeauthorisationWorker = new PersonDeauthorisationWorker(dryRun, this.authorityService,
                (AuthorizationService) this.authorisationService);

        if (!work.isEmpty())
        {
            // needs to run single-threaded to try and avoid issues with authorisationService
            // (observed quite a lot of update conflicts and inconsistent state in multi-threaded updates)
            final BatchProcessor<DeauthorisationUserInfo> processor = new BatchProcessor<>("DeauthoriseInactiveUsers",
                    this.transactionService.getRetryingTransactionHelper(), new CollectionWrappingWorkProvider<>(work, 1), 1,
                    this.batchSize, null, LogFactory.getLog(DeauthoriseInactiveUsers.class), this.loggingInterval);
            processor.process(personDeauthorisationWorker, true);
        }

        final int deauthorised = personDeauthorisationWorker.getDeauthorised();
        model.put("deauthorised", Integer.valueOf(deauthorised));
        if (!dryRun)
        {
            // need nested transaction for an "after" count
            this.transactionService.getRetryingTransactionHelper().doInTransaction(() -> {
                final long authorizedUsersCount2 = ((AuthorizationService) this.authorisationService).getAuthorizedUsersCount();
                model.put("authorisedUsersAfter", Long.valueOf(authorizedUsersCount2));
                return null;
            }, true, true);
        }
        else
        {
            model.put("authorisedUsersAfter", Long.valueOf(authorizedUsersCount - deauthorised));
        }

        return model;
    }
}
