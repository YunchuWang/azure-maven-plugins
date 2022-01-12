/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.common.model;

import com.microsoft.azure.toolkit.lib.AzService;
import com.microsoft.azure.toolkit.lib.Azure;
import com.microsoft.azure.toolkit.lib.account.IAzureAccount;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nonnull;
import java.util.stream.Stream;

public abstract class AbstractAzService<T extends AbstractAzResource<T, AzResource.None, R>, R> extends AbstractAzResourceModule<T, AzResource.None, R>
    implements AzService {

    public AbstractAzService(@Nonnull String name) {
        super(name, AzResource.NONE);
    }

    public T forSubscription(@Nonnull String subscriptionId) {
        return this.get(subscriptionId, null);
    }

    @Override
    public void refresh() {
        super.refresh();
        this.list().forEach(AbstractAzResource::refresh);
    }

    @Nonnull
    @Override
    protected Stream<R> loadResourcesFromAzure() {
        return Azure.az(IAzureAccount.class).account().getSelectedSubscriptions().stream().parallel()
            .map(Subscription::getId).map(i -> loadResourceFromAzure(i, null));
    }

    @Nonnull
    @Override
    public String toResourceId(@Nonnull String resourceName, String resourceGroup) {
        final String rg = StringUtils.firstNonBlank(resourceGroup, AzResource.RESOURCE_GROUP_PLACEHOLDER);
        return String.format("/subscriptions/%s/resourceGroups/%s/providers/%s", resourceName, rg, this.getName());
    }
}
