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
package de.acosix.alfresco.deauth.repo.job;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import org.alfresco.enterprise.repo.authorization.AuthorizationService;
import org.alfresco.repo.batch.BatchProcessor;
import org.alfresco.repo.lock.LockAcquisitionException;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.service.cmr.audit.AuditService;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.search.SearchService;
import org.alfresco.service.cmr.security.AuthorityService;
import org.alfresco.service.cmr.security.PersonService;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;
import org.alfresco.service.transaction.TransactionService;
import org.apache.commons.logging.LogFactory;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.acosix.alfresco.audit.repo.batch.AuditUserInfo;
import de.acosix.alfresco.audit.repo.batch.AuditUserInfo.AuthorisedState;
import de.acosix.alfresco.audit.repo.batch.PersonAuditWorker;
import de.acosix.alfresco.audit.repo.batch.PersonAuditWorker.PersonAuditQueryMode;
import de.acosix.alfresco.deauth.repo.DeauthModuleConstants;
import de.acosix.alfresco.deauth.repo.batch.DeauthorisationUserInfo;
import de.acosix.alfresco.deauth.repo.batch.PersonDeauthorisationWorker;
import de.acosix.alfresco.utility.repo.batch.CollectionWrappingWorkProvider;
import de.acosix.alfresco.utility.repo.batch.PersonBatchWorkProvider;
import de.acosix.alfresco.utility.repo.job.JobUtilities;

/**
 * This job performs periodic deauthorisation of any inactive users (determined by analysing audit data).
 *
 * @author Axel Faust
 */
public class DeauthoriseInactiveUsersJob implements Job
{

    public static enum LookBackMode
    {
        YEARS, MONTHS, DAYS;
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(DeauthoriseInactiveUsersJob.class);

    protected static final int DEFAULT_LOOK_BACK_DAYS = 90;

    protected static final int DEFAULT_LOOK_BACK_MONTHS = 3;

    protected static final int DEFAULT_LOOK_BACK_YEARS = 1;

    protected static final int DEFAULT_WORKER_THREADS = 4;

    protected static final int DEFAULT_BATCH_SIZE = 20;

    protected static final int DEFAULT_LOGGING_INTERVAL = 100;

    protected static final QName LOCK_QNAME = QName.createQName(DeauthModuleConstants.SERVICE_NAMESPACE,
            DeauthoriseInactiveUsersJob.class.getSimpleName());

    /**
     * {@inheritDoc}
     */
    @Override
    public void execute(final JobExecutionContext context) throws JobExecutionException
    {
        LOGGER.debug("Running deauthorisation of inactive users");
        try
        {
            AuthenticationUtil.runAsSystem(() -> {
                JobUtilities.runWithJobLock(context, LOCK_QNAME, lockReleaseCheck -> {
                    final TransactionService transactionService = JobUtilities.getJobDataValue(context, "transactionService",
                            TransactionService.class);
                    transactionService.getRetryingTransactionHelper().doInTransaction(() -> {
                        this.deauthoriseInactiveUsers(context);
                        return null;
                    }, false, true);
                });
                return null;
            });
            LOGGER.debug("Completed deauthorisation of inactive users");
        }
        catch (final Exception e)
        {
            if (!(e instanceof LockAcquisitionException))
            {
                LOGGER.debug("Deauthorisation of inactive users failed", e);
            }
        }
    }

    protected void deauthoriseInactiveUsers(final JobExecutionContext context)
    {
        final TransactionService transactionService = JobUtilities.getJobDataValue(context, "transactionService", TransactionService.class);
        final AuthorizationService authorisationService = JobUtilities.getJobDataValue(context, "authorisationService",
                AuthorizationService.class);

        final String workerThreadsParam = JobUtilities.getJobDataValue(context, "workerThreads", String.class);
        final String batchSizeParam = JobUtilities.getJobDataValue(context, "batchSize", String.class);
        final String loggingIntervalParam = JobUtilities.getJobDataValue(context, "loggingInterval", String.class);

        int workerThreads = DEFAULT_WORKER_THREADS;
        if (workerThreadsParam != null)
        {
            workerThreads = Integer.parseInt(workerThreadsParam, 10);
            if (workerThreads <= 0)
            {
                throw new IllegalStateException("Number of worker threads must be a positive integer");
            }
        }

        int batchSize = DEFAULT_BATCH_SIZE;
        if (batchSizeParam != null)
        {
            batchSize = Integer.parseInt(batchSizeParam, 10);
            if (batchSize <= 0)
            {
                throw new IllegalStateException("Batch size must be a positive integer");
            }
        }

        int loggingInterval = DEFAULT_LOGGING_INTERVAL;
        if (loggingIntervalParam != null)
        {
            loggingInterval = Integer.parseInt(loggingIntervalParam, 10);
            if (loggingInterval <= 0)
            {
                throw new IllegalStateException("Logging interval must be a positive integer");
            }
        }

        final List<AuditUserInfo> inactiveUsers = this.queryInactiveUsers(workerThreads, batchSize, loggingInterval, transactionService,
                authorisationService, context);
        final List<DeauthorisationUserInfo> work = new ArrayList<>();
        inactiveUsers.stream().filter(user -> {
            final boolean relevant = user.getAuthorisedState() == AuthorisedState.AUTHORISED;
            return relevant;
        }).forEach(user -> {
            work.add(new DeauthorisationUserInfo(user));
        });

        if (work.isEmpty())
        {
            LOGGER.info("No inactive users to deauthorise");
        }
        else
        {
            this.deauthoriseInactiveUsers(work, workerThreads, batchSize, loggingInterval, transactionService, authorisationService,
                    context);
        }
    }

    protected void deauthoriseInactiveUsers(final List<DeauthorisationUserInfo> work, final int workerThreads, final int batchSize,
            final int loggingInterval, final TransactionService transactionService, final AuthorizationService authorisationService,
            final JobExecutionContext context)
    {
        final AuthorityService authorityService = JobUtilities.getJobDataValue(context, "authorityService", AuthorityService.class);

        final String dryRunParam = JobUtilities.getJobDataValue(context, "dryRun", String.class);
        final boolean dryRun = Boolean.parseBoolean(dryRunParam);

        final PersonDeauthorisationWorker personDeauthorisationWorker = new PersonDeauthorisationWorker(dryRun, authorityService,
                authorisationService);

        LOGGER.info("Running deauthorisation job on {} inactive users{}", work.size(), dryRun ? " (dry-run)" : "");

        // needs to run single-threaded to try and avoid issues with authorisationService
        // (observed quite a lot of update conflicts and inconsistent state in multi-threaded updates)
        final BatchProcessor<DeauthorisationUserInfo> processor = new BatchProcessor<>("DeauthoriseInactiveUsers",
                transactionService.getRetryingTransactionHelper(), new CollectionWrappingWorkProvider<>(work, 1), 1, batchSize, null,
                LogFactory.getLog(this.getClass().getName() + ".batchProcessor"), loggingInterval);
        processor.process(personDeauthorisationWorker, true);

        final int deauthorised = personDeauthorisationWorker.getDeauthorised();
        LOGGER.info("Deauthorised {} inactive users", deauthorised);
    }

    protected List<AuditUserInfo> queryInactiveUsers(final int workerThreads, final int batchSize, final int loggingInterval,
            final TransactionService transactionService, final AuthorizationService authorisationService, final JobExecutionContext context)
    {
        final NamespaceService namespaceService = JobUtilities.getJobDataValue(context, "namespaceService", NamespaceService.class);
        final NodeService nodeService = JobUtilities.getJobDataValue(context, "nodeService", NodeService.class);
        final PersonService personService = JobUtilities.getJobDataValue(context, "personService", PersonService.class);
        final SearchService searchService = JobUtilities.getJobDataValue(context, "searchService", SearchService.class);
        final AuditService auditService = JobUtilities.getJobDataValue(context, "auditService", AuditService.class);

        final String lookBackModeParam = JobUtilities.getJobDataValue(context, "lookBackMode", String.class, true);
        final String lookBackAmountParam = JobUtilities.getJobDataValue(context, "lookBackAmount", String.class, true);

        final String auditApplicationName = JobUtilities.getJobDataValue(context, "auditApplicationName", String.class);
        final String userAuditPath = JobUtilities.getJobDataValue(context, "userAuditPath", String.class, true);
        final String dateAuditPath = JobUtilities.getJobDataValue(context, "dateAuditPath", String.class, true);
        final String dateFromAuditPath = JobUtilities.getJobDataValue(context, "dateFromAuditPath", String.class, true);
        final String dateToAuditPath = JobUtilities.getJobDataValue(context, "dateToAuditPath", String.class, true);

        LookBackMode lookBackMode = LookBackMode.MONTHS;
        if (lookBackModeParam != null)
        {
            lookBackMode = LookBackMode.valueOf(lookBackModeParam.toUpperCase(Locale.ENGLISH));
        }

        int lookBackAmount;
        if (lookBackAmountParam != null)
        {
            lookBackAmount = Integer.parseInt(lookBackAmountParam, 10);
            if (lookBackAmount <= 0)
            {
                throw new IllegalStateException("Amount of time to look back must be a positive integer");
            }
        }
        else
        {
            switch (lookBackMode)
            {
                case DAYS:
                    lookBackAmount = DEFAULT_LOOK_BACK_DAYS;
                    break;
                case MONTHS:
                    lookBackAmount = DEFAULT_LOOK_BACK_MONTHS;
                    break;
                case YEARS:
                    lookBackAmount = DEFAULT_LOOK_BACK_YEARS;
                    break;
                default:
                    throw new UnsupportedOperationException("Unsupported mode: " + lookBackMode);
            }
        }

        final Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ENGLISH);
        switch (lookBackMode)
        {
            case DAYS:
                cal.add(Calendar.DATE, -lookBackAmount);
                break;
            case MONTHS:
                cal.add(Calendar.MONTH, -lookBackAmount);
                break;
            case YEARS:
                cal.add(Calendar.YEAR, -lookBackAmount);
                break;
            default:
                throw new UnsupportedOperationException("Unsupported mode: " + lookBackMode);
        }
        final long fromTime = cal.getTimeInMillis();

        LOGGER.debug("Querying for inactive users (no activity since {}) via audit application {}", fromTime, auditApplicationName);
        LOGGER.trace("Using userAuditPath {}, dateAuditPath {}, dateFromAuditPath {}, dateToAuditPath {}", userAuditPath, dateAuditPath,
                dateFromAuditPath, dateToAuditPath);

        final PersonAuditWorker personAuditWorker = new PersonAuditWorker(fromTime, PersonAuditQueryMode.INACTIVE_ONLY,
                auditApplicationName, nodeService, auditService);

        personAuditWorker.setUserAuditPath(userAuditPath);
        personAuditWorker.setDateAuditPath(dateAuditPath);
        personAuditWorker.setDateFromAuditPath(dateFromAuditPath);
        personAuditWorker.setDateToAuditPath(dateToAuditPath);

        personAuditWorker.setIsAuthorisedCheck(userName -> {
            final boolean authorized = authorisationService.isAuthorized(userName);
            return Boolean.valueOf(authorized);
        });
        personAuditWorker.setIsDeauthorisedCheck(userName -> {
            final boolean deauthorized = authorisationService.isDeauthorized(userName);
            return Boolean.valueOf(deauthorized);
        });

        final BatchProcessor<NodeRef> processor = new BatchProcessor<>("DeauthoriseInactiveUsers-PreparationQuery",
                transactionService.getRetryingTransactionHelper(),
                new PersonBatchWorkProvider(namespaceService, nodeService, personService, searchService), workerThreads, batchSize, null,
                LogFactory.getLog(this.getClass().getName() + ".batchProcessor"), loggingInterval);

        processor.process(personAuditWorker, true);

        final List<AuditUserInfo> auditUsers = new ArrayList<>(personAuditWorker.getUsers());
        Collections.sort(auditUsers);
        return auditUsers;
    }
}
