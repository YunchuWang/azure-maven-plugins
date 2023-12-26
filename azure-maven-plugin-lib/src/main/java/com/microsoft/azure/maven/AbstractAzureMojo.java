/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.maven;

import com.azure.core.http.policy.HttpLogDetailLevel;
import com.azure.core.management.AzureEnvironment;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.microsoft.applicationinsights.internal.channel.common.ApacheSenderFactory;
import com.microsoft.azure.maven.exception.MavenDecryptException;
import com.microsoft.azure.maven.model.MavenAuthConfiguration;
import com.microsoft.azure.maven.model.SubscriptionOption;
import com.microsoft.azure.maven.utils.CustomTextIoStringListReader;
import com.microsoft.azure.maven.utils.MavenAuthUtils;
import com.microsoft.azure.maven.utils.SystemPropertyUtils;
import com.microsoft.azure.maven.utils.TextIOUtils;
import com.microsoft.azure.toolkit.lib.Azure;
import com.microsoft.azure.toolkit.lib.auth.Account;
import com.microsoft.azure.toolkit.lib.auth.AuthConfiguration;
import com.microsoft.azure.toolkit.lib.auth.AuthType;
import com.microsoft.azure.toolkit.lib.auth.AzureAccount;
import com.microsoft.azure.toolkit.lib.auth.AzureCloud;
import com.microsoft.azure.toolkit.lib.auth.AzureEnvironmentUtils;
import com.microsoft.azure.toolkit.lib.auth.AzureToolkitAuthenticationException;
import com.microsoft.azure.toolkit.lib.common.bundle.AzureString;
import com.microsoft.azure.toolkit.lib.common.exception.AzureExecutionException;
import com.microsoft.azure.toolkit.lib.common.exception.AzureToolkitRuntimeException;
import com.microsoft.azure.toolkit.lib.common.messager.AzureMessager;
import com.microsoft.azure.toolkit.lib.common.messager.IAzureMessager;
import com.microsoft.azure.toolkit.lib.common.model.Subscription;
import com.microsoft.azure.toolkit.lib.common.operation.OperationContext;
import com.microsoft.azure.toolkit.lib.common.proxy.ProxyInfo;
import com.microsoft.azure.toolkit.lib.common.proxy.ProxyManager;
import com.microsoft.azure.toolkit.lib.common.telemetry.AzureTelemeter;
import com.microsoft.azure.toolkit.lib.common.telemetry.AzureTelemetryClient;
import com.microsoft.azure.toolkit.lib.common.utils.InstallationIdUtils;
import com.microsoft.azure.toolkit.lib.common.utils.TextUtils;
import com.microsoft.azure.toolkit.lib.common.utils.Utils;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Proxy;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.shared.filtering.MavenResourcesFiltering;
import org.beryx.textio.TextTerminal;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Base abstract class for all Azure Mojos.
 */
@Slf4j
public abstract class AbstractAzureMojo extends AbstractMojo {
    public static final String PLUGIN_NAME_KEY = "pluginName";
    public static final String PLUGIN_VERSION_KEY = "pluginVersion";
    public static final String INSTALLATION_ID_KEY = "installationId";
    public static final String SESSION_ID_KEY = "sessionId";
    public static final String SUBSCRIPTION_ID_KEY = "subscriptionId";
    private static final String AUTH_TYPE = "authType";
    private static final String AUTH_METHOD = "authMethod";
    private static final String TELEMETRY_NOT_ALLOWED = "TelemetryNotAllowed";
    private static final String JVM_UP_TIME = "jvmUpTime";
    private static final String CONFIGURATION_PATH = Paths.get(System.getProperty("user.home"),
            ".azure", "mavenplugins.properties").toString();
    private static final String FIRST_RUN_KEY = "first.run";
    private static final String PRIVACY_STATEMENT = "\nData/Telemetry\n" +
            "---------\n" +
            "This project collects usage data and sends it to Microsoft to help improve our products and services.\n" +
            "Read Microsoft's privacy statement to learn more: https://privacy.microsoft.com/en-us/privacystatement." +
            "\n\nYou can change your telemetry configuration through 'allowTelemetry' property.\n" +
            "For more information, please go to https://aka.ms/azure-maven-config.\n";
    protected static final String SUBSCRIPTION_TEMPLATE = "Subscription: %s(%s)";
    protected static final String USING_AZURE_ENVIRONMENT = "Using Azure environment: %s.";
    protected static final String SUBSCRIPTION_NOT_FOUND = "Subscription %s was not found in current account.";
    protected static final String COMPILE_LEVEL_NOT_SUPPORTED = "Your project's compile level (%s) is not compatible with any of the supported runtimes of Azure. " +
            "The supported runtimes include %s. Please reset the compile level to a compatible version in case any compatibility issues.";
    protected static final String FAILED_TO_GET_VALID_RUNTIMES = "Failed to get valid runtime based on project's compile level, fall back to all values";

    private static final String AZURE_ENVIRONMENT = "azureEnvironment";
    private static final String PROXY = "proxy";
    private static final String INVALID_ARTIFACT = "The artifact's compile level (%s) is not compatible with runtime '%s'. " +
            "Please rebuild the artifact with a lower level or switch to a higher Java runtime.";
    private static final String SKIP_VALIDATION_MESSAGE = "To skip this validation, set 'failsOnRuntimeValidationError' to 'false' in the command line or pom.xml";

    //region Properties

    @Getter
    @JsonIgnore
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Getter
    @JsonIgnore
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    protected MavenSession session;

    @JsonIgnore
    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    protected File buildDirectory;

    @JsonIgnore
    @Parameter(defaultValue = "${plugin}", readonly = true, required = true)
    protected PluginDescriptor plugin;

    /**
     * The system settings for Maven. This is the instance resulting from
     * merging global and user-level settings files.
     */
    @Getter
    @JsonIgnore
    @Parameter(defaultValue = "${settings}", readonly = true, required = true)
    protected Settings settings;

    @Getter
    @JsonIgnore
    @Component(role = MavenResourcesFiltering.class, hint = "default")
    protected MavenResourcesFiltering mavenResourcesFiltering;

    /**
     * Azure subscription id. Required only if there are more than one subscription in your account
     * @since 0.1.0
     */
    @JsonProperty
    @Parameter
    @Getter
    protected String subscriptionId = null;

    /**
     * Boolean flag to turn on/off telemetry within current Maven plugin.
     * @since 0.1.0
     */
    @Getter
    @JsonProperty
    @Parameter(property = "allowTelemetry", defaultValue = "true")
    protected Boolean allowTelemetry;

    /**
     * Boolean flag to control whether throwing exception from current Maven plugin when meeting any error.<p>
     * If set to true, the exception from current Maven plugin will fail the current Maven run.
     * @since 0.1.0
     */
    @Getter
    @JsonProperty
    @Parameter(property = "failsOnError", defaultValue = "true")
    protected Boolean failsOnError;

    /**
     * Deprecated, please set the authentication type in `auth`
     * @since 1.2.13
     */
    @JsonProperty
    @Deprecated
    @Parameter(property = "authType")
    protected String authType;

    /**
     * Configuration for maven plugin authentication
     * Parameters for authentication
     * <ul>
     *     <li> type: specify which authentication method to use, default to be `auto`:
     *         <ul>
     *             <li> service_principal : Will use credential specified in plugin configuration or Maven settings.xml,
     *             this is also the first priority authentication method in auto</li>
     *             <li> managed_identity : Will use the system managed credential managed by azure</li>
     *             <li> azure_cli : Will use credential provided by Azure CLI, this could also be used in Azure Cloud Shell</li>
     *             <li> oauth2 : Will use credential provided by oauth2, a browser will be opened, you need to follow the page to follow the login process</li>
     *             <li> device_code : Similar to oauth2, it provides you a login-code together with an url, you need to open a browser at any machine and fill-in
     *             the login-code, then you can follow the page to follow to finish the login process on the web page</li>
     *             <li> auto: The default auth type, it will try all the auth methods in the following sequence: service_principal, managed_identity, azure_cli, oauth2, device_code </li>
     *         </ul>
     *     </li>
     *     <li> environment: Specifies the target Azure cloud environment<p>
     *         Supported values are: `azure`, `azure_china`, `azure_germany`, `azure_us_government`
     *     </li>
     *     <li> client: Client ID of the service principal.</li>
     *     <li> tenant: Tenant ID of the service principal.</li>
     *     <li> key: Specifies the password if your service principal uses password authentication.</li>
     *     <li> certificate: The absolute path of certificate, required if your service principal uses certificate authentication.<p>
     *          Note: Only PKCS12 certificates are supported.</li>
     *     <li> certificatePassword: Password for the certificate</li>
     *     <li> serverId: Reference for service principal configuration in Maven settings.xml, please see the example below </li>
     * </ul>
     * Sample: Service principal configuration <p>
     * <pre>
     * {@code
     * <configuration>
     *     <auth>
     *         <client>xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx</client>
     *         <tenant>yyyyyyyy-yyyy-yyyy-yyyy-yyyyyyyyyyyy</tenant>
     *         <key>zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz</key>
     *         <environment>azure</environment>
     *     </auth>
     * </configuration>
     * }
     * </pre>
     * Sample: Service principal configuration within maven settings.xml <p>
     * Pom.xml
     * <pre>
     * {@code
     * <configuration>
     *      <auth>
     *          <type>service_principal</type>
     *          <serverId>azure-sp-auth1</serverId>
     *       </auth>
     * </configuration>
     * }
     * </pre>
     * Settings.xml
     * <pre>
     * {@code
     * <server>
     *    <id>azure-sp-auth1</id>
     *    <configuration>
     *        <client>xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx</client>
     *        <tenant>yyyyyyyy-yyyy-yyyy-yyyy-yyyyyyyyyyyy</tenant>
     *        <key>xxx</key>
     *        <environment>azure</environment>
     *    </configuration>
     * </server>
     * }
     * </pre>
     */
    @JsonProperty
    @Parameter(property = "auth")
    protected MavenAuthConfiguration auth;

    /**
     * Boolean flag to control whether throwing exception when runtime validation failed
     */
    @JsonProperty
    @Getter
    @Parameter(property = "failsOnRuntimeValidationError", defaultValue = "true")
    protected Boolean failsOnRuntimeValidationError;

    @Component
    @JsonIgnore
    protected SettingsDecrypter settingsDecrypter;

    @Getter
    @JsonIgnore
    protected AzureTelemetryClient telemetryProxy;

    @Getter
    @JsonIgnore
    protected Map<String, String> telemetries = new HashMap<>();

    @Getter
    @JsonIgnore
    private final String sessionId = UUID.randomUUID().toString();

    @Getter
    @JsonIgnore
    private final String installationId = Optional.ofNullable(InstallationIdUtils.getHashMac()).orElse("");

    //region Entry Point

    @Override
    public void execute() throws MojoExecutionException {
        final String originalReflectionLogLevel = System.getProperty("org.slf4j.simpleLogger.log.org.reflections.Reflections");
        try {
            System.setProperty("org.slf4j.simpleLogger.log.org.reflections.Reflections", "warn");
            Azure.az().config().setLogLevel(HttpLogDetailLevel.NONE.name());
            Azure.az().config().setUserAgent(getUserAgent());
            Azure.az().config().setProduct(getPluginName());
            Azure.az().config().setVersion(getPluginVersion());
            Azure.az().config().setTelemetryEnabled(BooleanUtils.isNotFalse(allowTelemetry));
            // init proxy manager
            initMavenSettingsProxy(Optional.ofNullable(this.session).map(MavenSession::getRequest).orElse(null));
            ProxyManager.getInstance().applyProxy();
            initTelemetryProxy();
            telemetryProxy.addDefaultProperty(PROXY, String.valueOf(ProxyManager.getInstance().isProxyEnabled()));
            // Work around for Application Insights Java SDK:
            // Sometimes, NoClassDefFoundError will be thrown even after Maven build is completed successfully.
            // An issue has been filed at https://github.com/Microsoft/ApplicationInsights-Java/issues/416
            // Before this issue is fixed, set default uncaught exception handler for all threads as work around.
            Thread.setDefaultUncaughtExceptionHandler(new DefaultUncaughtExceptionHandler());

            final Properties prop = new Properties();
            if (isFirstRun(prop)) {
                infoWithMultipleLines(PRIVACY_STATEMENT);
                updateConfigurationFile(prop);
            }

            if (isSkipMojo()) {
                log.info("Skip execution.");
                onSkipped();
            } else {
                beforeMojoExecution();
                doExecute();
                afterMojoExecution();
            }
        } catch (Throwable e) {
            onMojoError(e);
        } finally {
            // When maven goal executes too quick, The HTTPClient of AI SDK may not fully initialize and will step
            // into endless loop when close, we need to call it in main thread.
            // Refer here for detail codes: https://github.com/Microsoft/ApplicationInsights-Java/blob/master/core/src
            // /main/java/com/microsoft/applicationinsights/internal/channel/common/ApacheSender43.java#L103
            Optional.ofNullable(TextIOUtils.getTextTerminal()).ifPresent(TextTerminal::dispose);
            try {
                // Sleep to wait ai sdk flush telemetries
                Thread.sleep(2 * 1000);
            } catch (InterruptedException e) {
                // swallow this exception
            }
            if (Objects.nonNull(originalReflectionLogLevel)) {
                System.setProperty("org.slf4j.simpleLogger.log.org.reflections.Reflections", originalReflectionLogLevel);
            } else {
                System.clearProperty("org.slf4j.simpleLogger.log.org.reflections.Reflections");
            }
            ProxyManager.getInstance().resetProxy();
            ApacheSenderFactory.INSTANCE.create().close();
        }
    }

    //endregion

    public String getBuildDirectoryAbsolutePath() {
        return buildDirectory.getAbsolutePath();
    }

    public String getPluginName() {
        return plugin.getArtifactId();
    }

    public String getPluginVersion() {
        return plugin.getVersion();
    }

    public String getUserAgent() {
        return BooleanUtils.isNotFalse(allowTelemetry) ? String.format("%s/%s %s:%s %s:%s", getPluginName(), getPluginVersion(),
            INSTALLATION_ID_KEY, getInstallationId(), SESSION_ID_KEY, getSessionId())
            : String.format("%s/%s", getPluginName(), getPluginVersion());
    }

    protected Account loginAzure() throws AzureExecutionException, MavenDecryptException {
        return this.loginAzure(auth);
    }

    protected Account loginAzure(MavenAuthConfiguration auth) throws MavenDecryptException, AzureExecutionException {
        if (Azure.az(AzureAccount.class).isLoggedIn()) {
            return Azure.az(AzureAccount.class).account();
        }
        final AuthConfiguration authConfig = toAuthConfiguration(auth);
        if (authConfig.getType() == AuthType.DEVICE_CODE) {
            authConfig.setDeviceCodeConsumer(info -> {
                final String message = StringUtils.replace(info.getMessage(), info.getUserCode(), TextUtils.cyan(info.getUserCode()));
                System.out.println(message);
            });
        }
        final AzureEnvironment configEnv = AzureEnvironmentUtils.stringToAzureEnvironment(authConfig.getEnvironment());
        promptAzureEnvironment(configEnv);
        Azure.az(AzureCloud.class).set(configEnv);
        MavenAuthUtils.disableIdentityLogs();
        final Account account = Azure.az(AzureAccount.class).login(authConfig, false);
        final AzureEnvironment env = account.getEnvironment();
        final String environmentName = AzureEnvironmentUtils.azureEnvironmentToString(env);
        if (env != AzureEnvironment.AZURE && env != configEnv) {
            AzureMessager.getMessager().info(AzureString.format(USING_AZURE_ENVIRONMENT, environmentName));
        }
        printCredentialDescription(account);
        telemetryProxy.addDefaultProperty(AUTH_TYPE, account.getType().toString());
        telemetryProxy.addDefaultProperty(AUTH_METHOD, getActualAuthType());
        telemetryProxy.addDefaultProperty(AZURE_ENVIRONMENT, environmentName);
        return account;
    }

    private AuthConfiguration toAuthConfiguration(MavenAuthConfiguration auth) throws AzureExecutionException, MavenDecryptException {
        final MavenAuthConfiguration mavenAuthConfiguration = auth == null ? new MavenAuthConfiguration() : auth;
        mavenAuthConfiguration.setType(StringUtils.firstNonBlank(mavenAuthConfiguration.getType(), authType));
        SystemPropertyUtils.injectCommandLineParameter("auth", mavenAuthConfiguration, MavenAuthConfiguration.class);
        return MavenAuthUtils.buildAuthConfiguration(session, settingsDecrypter, mavenAuthConfiguration);
    }

    protected String selectSubscription(Subscription[] subscriptions) throws AzureExecutionException {
        if (subscriptions.length == 0) {
            throw new AzureExecutionException("Cannot find any subscriptions in current account.");
        }
        if (subscriptions.length == 1) {
            log.info(String.format("There is only one subscription '%s' in your account, will use it automatically.",
                TextUtils.blue(SubscriptionOption.getSubscriptionName(subscriptions[0]))));
            return subscriptions[0].getId();
        }
        final List<SubscriptionOption> wrapSubs = Arrays.stream(subscriptions).map(SubscriptionOption::new)
            .sorted()
            .collect(Collectors.toList());
        final SubscriptionOption defaultValue = wrapSubs.get(0);
        final SubscriptionOption subscriptionOptionSelected = new CustomTextIoStringListReader<SubscriptionOption>(TextIOUtils::getTextTerminal, null)
            .withCustomPrompt(String.format("Please choose a subscription%s: ",
                highlightDefaultValue(defaultValue == null ? null : defaultValue.getSubscriptionName())))
            .withNumberedPossibleValues(wrapSubs).withDefaultValue(defaultValue).read("Available subscriptions:");
        if (subscriptionOptionSelected == null) {
            throw new AzureExecutionException("You must select a subscription.");
        }
        return subscriptionOptionSelected.getSubscription().getId();
    }

    @SneakyThrows
    protected void selectSubscription() {
        final Account account = Azure.az(AzureAccount.class).account();
        final List<Subscription> subscriptions = account.getSubscriptions();
        final String targetSubscriptionId = getTargetSubscriptionId(getSubscriptionId(), subscriptions, account.getSelectedSubscriptions());
        checkSubscription(subscriptions, targetSubscriptionId);
        account.setSelectedSubscriptions(Collections.singletonList(targetSubscriptionId));
        final Subscription subscription = account.getSubscription(targetSubscriptionId);
        log.info(String.format(SUBSCRIPTION_TEMPLATE, TextUtils.cyan(subscription.getName()), TextUtils.cyan(subscription.getId())));
        this.subscriptionId = targetSubscriptionId;
    }

    private static void promptAzureEnvironment(AzureEnvironment env) {
        if (env != null && env != AzureEnvironment.AZURE) {
            AzureMessager.getMessager().info(AzureString.format("Auth environment: %s", AzureEnvironmentUtils.azureEnvironmentToString(env)));
        }
    }

    protected static void printCredentialDescription(Account account) {
        final IAzureMessager messager = AzureMessager.getMessager();
        final String username = account.getUsername();
        if (StringUtils.isNotEmpty(username)) {
            messager.info(AzureString.format("Username: %s", username.trim()));
        }
    }

    protected void initTelemetryProxy() {
        final Map<String, String> properties = getTelemetryProperties();
        AzureTelemeter.addCommonProperties(properties);
        this.telemetryProxy = AzureTelemeter.getClient();
        AzureTelemeter.setEventNamePrefix("AzurePlugin.Maven");
        if (!BooleanUtils.isNotFalse(allowTelemetry)) {
            telemetryProxy.trackEvent(TELEMETRY_NOT_ALLOWED);
        }
    }

    //endregion
    public Map<String, String> getTelemetryProperties() {
        final Map<String, String> map = new HashMap<>();
        map.put(INSTALLATION_ID_KEY, getInstallationId());
        map.put(PLUGIN_NAME_KEY, getPluginName());
        map.put(PLUGIN_VERSION_KEY, getPluginVersion());
        map.put(SUBSCRIPTION_ID_KEY, getSubscriptionId());
        map.put(SESSION_ID_KEY, getSessionId());
        return map;
    }

    public String getActualAuthType() {
        final Account account = Azure.az(AzureAccount.class).account();
        if (account != null) {
            return account.getType().toString();
        }
        return "Unknown";
    }

    private static void initMavenSettingsProxy(MavenExecutionRequest request) {
        if (request != null) {
            final List<Proxy> mavenProxies = request.getProxies();
            if (CollectionUtils.isNotEmpty(mavenProxies)) {
                final Proxy mavenProxy = mavenProxies.stream().filter(
                    proxy -> proxy.isActive() && proxy.getPort() > 0 && StringUtils.isNotBlank(proxy.getHost())).findFirst().orElse(null);
                if (mavenProxy != null) {
                    final ProxyInfo mavenProxyInfo = ProxyInfo.builder()
                        .source("maven")
                        .host(mavenProxy.getHost())
                        .port(mavenProxy.getPort())
                        .username(mavenProxy.getUsername())
                        .password(mavenProxy.getPassword())
                        .build();
                    Azure.az().config().setProxyInfo(mavenProxyInfo);
                }
            }
        }
    }

    /**
     * Sub-class can override this method to decide whether skip execution.
     *
     * @return Boolean to indicate whether skip execution.
     */
    protected boolean isSkipMojo() {
        return false;
    }

    /**
     * Entry point of sub-class. Sub-class should implement this method to do real work.
     */
    protected abstract void doExecute() throws Throwable;

    protected void onSkipped() {
        telemetryProxy.trackEvent(this.getClass().getSimpleName() + ".skip");
    }

    protected void beforeMojoExecution() {
        telemetryProxy.trackEvent(this.getClass().getSimpleName() + ".start", this.getTelemetries(), null, false);
    }

    protected void afterMojoExecution() {
        telemetryProxy.trackEvent(this.getClass().getSimpleName() + ".success", recordJvmUpTime(new HashMap<>()));
    }

    protected void trackMojoFailure(final Throwable throwable) {
        final Map<String, String> failureParameters = new HashMap<>();
        failureParameters.put(AzureTelemeter.ERROR_MSG, throwable.getMessage());
        failureParameters.put(AzureTelemeter.ERROR_STACKTRACE, ExceptionUtils.getStackTrace(throwable));
        failureParameters.put(AzureTelemeter.ERROR_CLASSNAME, throwable.getClass().getName());
        Optional.ofNullable(ExceptionUtils.getRootCause(throwable)).ifPresent(root -> {
            failureParameters.put(AzureTelemeter.ERROR_ROOT_MSG, root.getMessage());
            failureParameters.put(AzureTelemeter.ERROR_ROOT_CLASSNAME, root.getClass().getName());
        });
        telemetryProxy.trackEvent(this.getClass().getSimpleName() + ".failure", recordJvmUpTime(failureParameters));
    }

    protected static String highlightDefaultValue(String defaultValue) {
        return StringUtils.isBlank(defaultValue) ? "" : String.format(" [%s]", TextUtils.blue(defaultValue));
    }

    protected void onMojoError(final Throwable exception) throws MojoExecutionException {
        trackMojoFailure(exception);

        if (BooleanUtils.isNotFalse(failsOnError)) {
            final String message = StringUtils.isEmpty(exception.getMessage()) ? exception.toString() : exception.getMessage();
            throw new MojoExecutionException(message, exception);
        } else {
            AzureMessager.getMessager().error(exception);
        }
    }

    protected void executeWithTimeRecorder(RunnableWithException operation, String name) throws AzureExecutionException {
        final long startTime = System.currentTimeMillis();
        try {
            operation.run();
        } catch (Exception e) {
            throw new AzureExecutionException(e.getMessage(), e);
        } finally {
            final long endTime = System.currentTimeMillis();
            telemetryProxy.addDefaultProperty(String.format("%s-cost", name), String.valueOf(endTime - startTime));
        }
    }

    private Map<String, String> recordJvmUpTime(Map<String, String> properties) {
        final long jvmUpTime = ManagementFactory.getRuntimeMXBean().getUptime();
        properties.put(JVM_UP_TIME, String.valueOf(jvmUpTime));
        return properties;
    }

    private boolean isFirstRun(Properties prop) {
        try {
            final File configurationFile = new File(CONFIGURATION_PATH);
            if (configurationFile.exists()) {
                try (InputStream input = new FileInputStream(CONFIGURATION_PATH)) {
                    prop.load(input);
                    final String firstRunValue = prop.getProperty(FIRST_RUN_KEY);
                    if (firstRunValue != null && !firstRunValue.isEmpty() && firstRunValue.equalsIgnoreCase("false")) {
                        return false;
                    }
                }
            } else {
                configurationFile.getParentFile().mkdirs();
                configurationFile.createNewFile();
            }
        } catch (Exception e) {
            // catch exceptions here to avoid blocking mojo execution.
            log.debug(e.getMessage());
        }
        return true;
    }

    private void updateConfigurationFile(Properties prop) {
        try (OutputStream output = new FileOutputStream(CONFIGURATION_PATH)) {
            prop.setProperty(FIRST_RUN_KEY, "false");
            prop.store(output, "Azure Maven Plugin configurations");
        } catch (Exception e) {
            // catch exceptions here to avoid blocking mojo execution.
            log.debug(e.getMessage());
        }
    }

    protected static class DefaultUncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
            log.debug("uncaughtException: " + e);
        }
    }

    //endregion

    //region Logging

    public void infoWithMultipleLines(final String messages) {
        final String[] messageArray = messages.split("\\n");
        for (final String line : messageArray) {
            log.info(line);
        }
    }

    public static void validateArtifactCompileVersion(String runtimeJavaVersion, final File artifact, final boolean failOnValidation) throws AzureToolkitRuntimeException {
        runtimeJavaVersion = StringUtils.replaceIgnoreCase(runtimeJavaVersion, "java", "").trim();
        final int runtimeVersion;
        final int artifactCompileVersion;
        try {
            runtimeVersion = Utils.getJavaMajorVersion(runtimeJavaVersion);
            artifactCompileVersion = Utils.getArtifactCompileVersion(artifact);
        } catch (RuntimeException e) {
            AzureMessager.getMessager().info("Failed to get version of your artifact, skip artifact compatibility test");
            return;
        }
        if (artifactCompileVersion <= runtimeVersion) {
            return;
        }
        final AzureString errorMessage = AzureString.format(INVALID_ARTIFACT, artifactCompileVersion, runtimeVersion);
        if (failOnValidation) {
            throw new AzureToolkitRuntimeException(errorMessage.getString() + System.lineSeparator() + SKIP_VALIDATION_MESSAGE);
        } else {
            AzureMessager.getMessager().warning(errorMessage);
        }
    }

    /**
     * @return java compile level, e.g. '8', '11', '17'
     */
    @Nonnull
    protected String getCompileLevel() {
        // order compile, target
        // refers https://github.com/apache/maven-compiler-plugin/blob/maven-compiler-plugin-3.11.0/src/main/java/org/apache/maven/plugin/compiler/AbstractCompilerMojo.java#L1286-L1291
        final String rawLevel = Stream.of("release", "target").map(this::getCompilerPluginConfigurationByName)
                .filter(StringUtils::isNotEmpty).findFirst().orElse("5");
        // refers https://github.com/apache/maven-compiler-plugin/blob/maven-compiler-plugin-3.11.0/src/main/java/org/apache/maven/plugin/compiler/AbstractCompilerMojo.java#L1293-L1295
        return StringUtils.startsWithIgnoreCase(rawLevel, "1.") ? StringUtils.substring(rawLevel, 2) : rawLevel;
    }

    @Nonnull
    protected <T> List<T> getValidRuntimes(final List<T> runtimes, final Function<T, Integer> levelGetter, final Function<T, String> stringGetter) {
        try {
            final String compileLevel = getCompileLevel();
            final Integer level = Integer.valueOf(compileLevel);
            final List<T> result = runtimes.stream()
                    .filter(t -> levelGetter.apply(t) >= level)
                    .collect(Collectors.toList());
            if (CollectionUtils.isEmpty(result)) {
                final String supportedRuntimes = runtimes.stream().map(stringGetter).distinct().collect(Collectors.joining(", "));
                AzureMessager.getMessager().warning(AzureString.format(COMPILE_LEVEL_NOT_SUPPORTED, compileLevel, supportedRuntimes));
                return runtimes;
            } else {
                return result;
            }
        } catch (RuntimeException e) {
            log.debug(FAILED_TO_GET_VALID_RUNTIMES, e);
            return runtimes;
        }
    }

    @Nullable
    private String getCompilerPluginConfigurationByName(final String propertyName) {
        return Optional.ofNullable(project)
                .map(p -> p.getPlugin(Plugin.constructKey("org.apache.maven.plugins", "maven-compiler-plugin")))
                .map(Plugin::getConfiguration)
                .filter(object -> object instanceof Xpp3Dom)
                .map(Xpp3Dom.class::cast)
                .map(configuration -> configuration.getChild(propertyName))
                .map(Xpp3Dom::getValue)
                .filter(StringUtils::isNotBlank)
                .orElseGet(() -> getMavenPropertyByName("maven.compiler." + propertyName));
    }

    @Nullable
    private String getMavenPropertyByName(@Nonnull final String name) {
        return Optional.ofNullable(project)
                .map(MavenProject::getProperties)
                .map(properties -> properties.getProperty(name))
                .filter(StringUtils::isNotBlank)
                .orElse(null);
    }

    protected interface RunnableWithException {
        void run() throws Exception;
    }
    //endregion

    protected String getTargetSubscriptionId(String defaultSubscriptionId,
                                             List<Subscription> subscriptions,
                                             List<Subscription> selectedSubscriptions) throws IOException, AzureExecutionException {
        String targetSubscriptionId = defaultSubscriptionId;

        if (StringUtils.isBlank(targetSubscriptionId) && selectedSubscriptions.size() == 1) {
            targetSubscriptionId = selectedSubscriptions.get(0).getId();
        }

        if (StringUtils.isBlank(targetSubscriptionId)) {
            return selectSubscription(subscriptions.toArray(new Subscription[0]));
        }
        telemetryProxy.addDefaultProperty(SUBSCRIPTION_ID_KEY, targetSubscriptionId);
        return targetSubscriptionId;
    }

    protected static void checkSubscription(List<Subscription> subscriptions, String targetSubscriptionId) throws AzureToolkitAuthenticationException {
        if (StringUtils.isEmpty(targetSubscriptionId)) {
            return;
        }
        final Optional<Subscription> optionalSubscription = subscriptions.stream()
            .filter(subscription -> StringUtils.equals(subscription.getId(), targetSubscriptionId))
            .findAny();
        if (!optionalSubscription.isPresent()) {
            throw new AzureToolkitAuthenticationException(String.format(SUBSCRIPTION_NOT_FOUND, targetSubscriptionId));
        }
    }

    protected void updateTelemetryProperties() {
        Optional.ofNullable(OperationContext.action().getTelemetryProperties()).ifPresent(properties ->
                properties.forEach((key, value) -> telemetryProxy.addDefaultProperty(key, value)));
    }
}
