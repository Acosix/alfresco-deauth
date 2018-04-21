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

import org.alfresco.util.ParameterCheck;

import de.acosix.alfresco.audit.repo.batch.AuditUserInfo;

/**
 * @author Axel Faust
 */
public class DeauthorisationUserInfo
{

    protected final AuditUserInfo auditUserInfo;

    protected boolean deauthorised;

    public DeauthorisationUserInfo(final AuditUserInfo auditUserInfo)
    {
        ParameterCheck.mandatory("auditUserInfo", auditUserInfo);
        this.auditUserInfo = auditUserInfo;
    }

    /**
     * @return the deauthorised
     */
    public boolean isDeauthorised()
    {
        return this.deauthorised;
    }

    /**
     * @param deauthorised
     *            the deauthorised to set
     */
    public void setDeauthorised(final boolean deauthorised)
    {
        this.deauthorised = deauthorised;
    }

    /**
     * @return the auditUserInfo
     */
    public AuditUserInfo getAuditUserInfo()
    {
        return this.auditUserInfo;
    }

}
