/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.common.model;

import com.azure.core.http.HttpClient;
import com.azure.core.http.ProxyOptions;
import com.azure.core.http.netty.NettyAsyncHttpClientBuilder;
import com.azure.core.http.policy.HttpLogOptions;
import com.azure.core.management.profile.AzureProfile;
import com.azure.resourcemanager.resources.ResourceManager;
import com.azure.resourcemanager.resources.fluentcore.policy.ProviderRegistrationPolicy;
import com.azure.resourcemanager.resources.models.ProviderResourceType;
import com.azure.resourcemanager.resources.models.Providers;
import com.microsoft.azure.toolkit.lib.AzService;
import com.microsoft.azure.toolkit.lib.Azure;
import com.microsoft.azure.toolkit.lib.AzureConfiguration;
import com.microsoft.azure.toolkit.lib.account.IAccount;
import com.microsoft.azure.toolkit.lib.account.IAzureAccount;
import com.microsoft.azure.toolkit.lib.common.operation.AzureOperation;
import com.microsoft.azure.toolkit.lib.common.proxy.ProxyInfo;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.resolver.AddressResolverGroup;
import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.resolver.NoopAddressResolverGroup;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nonnull;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.microsoft.azure.toolkit.lib.Azure.az;

public abstract class AbstractAzServiceSubscription<T extends AbstractAzResource<T, AzResource.None, R>, R>
    extends AbstractAzResource<T, AzResource.None, R> {
    protected AbstractAzServiceSubscription(@Nonnull String name, @Nonnull AbstractAzResourceModule<T, None, R> module) {
        super(name, AzResource.RESOURCE_GROUP_PLACEHOLDER, module);
    }

    @AzureOperation(name = "azure/$resource.list_supported_regions.type", params = {"resourceType"})
    public List<Region> listSupportedRegions(@Nonnull String resourceType) {
        final String provider = getService().getName();
        final String subscriptionId = this.getSubscriptionId();
        final List<Region> allRegionList = az(IAzureAccount.class).listRegions(subscriptionId);
        final List<Region> result = new ArrayList<>();
        final ResourceManager resourceManager = getResourceManager();
        resourceManager.providers().getByName(provider).resourceTypes()
            .stream().filter(type -> StringUtils.equalsIgnoreCase(type.resourceType(), resourceType))
            .findAny().map(ProviderResourceType::locations)
            .ifPresent(list -> result.addAll(list.stream().map(Region::fromName).filter(allRegionList::contains).collect(Collectors.toList())));
        return result.isEmpty() ? allRegionList : result;
    }

    @Nonnull
    @Override
    protected String loadStatus(@Nonnull R remote) {
        return Status.OK;
    }

    @Nonnull
    public AzService getService() {
        return ((AzService) this.getModule());
    }

    @Nonnull
    @Override
    public String getFullResourceType() {
        return this.getService().getName();
    }

    @Nonnull
    public ResourceManager getResourceManager() {
        final String subscriptionId = this.getSubscriptionId();
        return getResourceManager(subscriptionId);
    }

    @Nonnull
    public static ResourceManager getResourceManager(@Nonnull final String subscriptionId) {
        final IAccount account = az(IAzureAccount.class).account();
        final AzureConfiguration config = Azure.az().config();
        final AzureProfile azureProfile = new AzureProfile(account.getEnvironment());
        final Providers providers = ResourceManager.configure()
            .withHttpClient(getDefaultHttpClient())
            .withLogOptions(new HttpLogOptions().setLogLevel(config.getLogLevel()))
            .withPolicy(config.getUserAgentPolicy())
            .authenticate(account.getTokenCredential(subscriptionId), azureProfile)
            .withSubscription(subscriptionId).providers();
        return ResourceManager.configure()
            .withHttpClient(getDefaultHttpClient())
            .withLogOptions(new HttpLogOptions().setLogLevel(config.getLogLevel()))
            .withPolicy(config.getUserAgentPolicy())
            .withPolicy(new ProviderRegistrationPolicy(providers)) // add policy to auto register resource providers
            .authenticate(account.getTokenCredential(subscriptionId), azureProfile)
            .withSubscription(subscriptionId);
    }

    public static class HttpClientHolder {
        private static HttpClient defaultHttpClient = null;

        @Nonnull
        private static synchronized HttpClient getHttpClient() {
            if (defaultHttpClient != null) {
                return defaultHttpClient;
            }

            final AddressResolverGroup<? extends SocketAddress> resolverGroup;
            ProxyOptions proxyOptions = null;
            final AzureConfiguration config = Azure.az().config();
            final ProxyInfo proxy = config.getProxyInfo();
            if (Objects.nonNull(proxy) && StringUtils.isNotBlank(proxy.getSource())) {
                proxyOptions = new ProxyOptions(ProxyOptions.Type.HTTP, new InetSocketAddress(proxy.getHost(), proxy.getPort()));
                if (StringUtils.isNoneBlank(proxy.getUsername(), proxy.getPassword())) {
                    proxyOptions.setCredentials(proxy.getUsername(), proxy.getPassword());
                }
                resolverGroup = NoopAddressResolverGroup.INSTANCE;
            } else {
                resolverGroup = DefaultAddressResolverGroup.INSTANCE;
            }
            reactor.netty.http.client.HttpClient nettyHttpClient =
                reactor.netty.http.client.HttpClient.create()
                    .resolver(resolverGroup);
            if (Objects.nonNull(config.getSslContext())) {
                //noinspection deprecation
                nettyHttpClient = nettyHttpClient.secure(sslConfig -> sslConfig.sslContext(new JdkSslContext(config.getSslContext(), true, ClientAuth.NONE)));
            }
            final NettyAsyncHttpClientBuilder builder = new NettyAsyncHttpClientBuilder(nettyHttpClient);
            Optional.ofNullable(proxyOptions).map(builder::proxy);
            defaultHttpClient = builder.build();
            return defaultHttpClient;
        }
    }

    @Nonnull
    public static HttpClient getDefaultHttpClient() {
        return HttpClientHolder.getHttpClient();
    }
}
