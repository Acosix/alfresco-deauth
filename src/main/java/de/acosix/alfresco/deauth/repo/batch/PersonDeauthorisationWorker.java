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
package de.acosix.alfresco.deauth.repo.batch;

import java.util.concurrent.atomic.AtomicInteger;

import org.alfresco.enterprise.repo.authorization.AuthorizationService;
import org.alfresco.repo.batch.BatchProcessor.BatchProcessWorkerAdaptor;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.service.cmr.security.AuthorityService;
import org.alfresco.util.transaction.TransactionSupportUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A batch process worker implementation that deauthorises users with regards to the Enterprise-only license management.
 *
 * @author Axel Faust
 */
public class PersonDeauthorisationWorker extends BatchProcessWorkerAdaptor<DeauthorisationUserInfo>
{

    private static final Logger LOGGER = LoggerFactory.getLogger(PersonDeauthorisationWorker.class);

    private static final String TXN_KEY_RUN_INITIALISED = PersonDeauthorisationWorker.class.getName() + "-runInitialised";

    protected final String runAsUser = AuthenticationUtil.getRunAsUser();

    // use separate counters for total and txn local - txn may be rolled back after all
    protected final ThreadLocal<AtomicInteger> deauthorisedTxn = new ThreadLocal<AtomicInteger>()
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

    protected final AtomicInteger deauthorised = new AtomicInteger(0);

    protected final boolean dryRun;

    protected final AuthorityService authorityService;

    protected final AuthorizationService authorisationService;

    public PersonDeauthorisationWorker(final boolean dryRun, final AuthorityService authorityService,
            final AuthorizationService authorisationService)
    {
        this.dryRun = dryRun;
        this.authorityService = authorityService;
        this.authorisationService = authorisationService;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void beforeProcess() throws Throwable
    {
        AuthenticationUtil.setRunAsUser(this.runAsUser);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getIdentifier(final DeauthorisationUserInfo entry)
    {
        final String userName = entry.getAuditUserInfo().getUserName();
        return userName;
    }

    /**
     *
     * {@inheritDoc}
     */
    @Override
    public void process(final DeauthorisationUserInfo entry) throws Throwable
    {
        final Object runInitialised = TransactionSupportUtil.getResource(TXN_KEY_RUN_INITIALISED);
        if (!Boolean.TRUE.equals(runInitialised))
        {
            // reset cannot be in beforeProcess - only process is covered by retrying txn helper
            this.deauthorisedTxn.remove();

            TransactionSupportUtil.bindResource(TXN_KEY_RUN_INITIALISED, Boolean.TRUE);
        }
        final String userName = entry.getAuditUserInfo().getUserName();

        final boolean wasAuthorised = this.authorisationService.isAuthorized(userName);
        final boolean deauthorised;
        if (!(this.authorityService.isAdminAuthority(userName) || this.authorityService.isGuestAuthority(userName)))
        {
            if (wasAuthorised)
            {
                LOGGER.debug("Deauthorising user {}{}", userName, this.dryRun ? " (dry-run)" : "");
                if (!this.dryRun)
                {
                    this.authorisationService.deauthorize(userName);
                }
                this.deauthorisedTxn.get().incrementAndGet();
            }
            else
            {
                LOGGER.debug("Not deauthorising user {} which is not marked as being authorised", userName);
            }
            deauthorised = wasAuthorised;
        }
        else
        {
            LOGGER.debug("Not deauthorising special admin / guest authority user {}", userName);
            deauthorised = false;
        }
        entry.setDeauthorised(deauthorised);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void afterProcess() throws Throwable
    {
        this.deauthorised.addAndGet(this.deauthorisedTxn.get().intValue());
    }

    /**
     * Retrieves the number of users that were deauthorised.
     *
     * @return the number of deauthorised people
     */
    public int getDeauthorised()
    {
        return this.deauthorised.intValue();
    }
}
