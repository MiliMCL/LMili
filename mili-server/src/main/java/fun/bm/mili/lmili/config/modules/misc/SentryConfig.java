package fun.bm.mili.lmili.config.modules.misc;

import fun.bm.mili.config.TomlConfigData;
import gg.pufferfish.pufferfish.sentry.SentryManager;
import fun.bm.mili.lmili.config.IConfigModule;
import fun.bm.mili.lmili.config.flags.CommandSuggestions;
import fun.bm.mili.lmili.config.flags.ConfigClassInfo;
import fun.bm.mili.lmili.config.flags.ConfigInfo;
import fun.bm.mili.lmili.enums.EnumConfigCategory;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

@ConfigClassInfo(category = EnumConfigCategory.MISC, name = "sentry")
public class SentryConfig implements IConfigModule {

    @ConfigInfo(name = "dsn", comments =
            " Sentry DSN for improved error logging, leave blank to disable,\n" +
                    " Obtain from https://sentry.io/")
    public static String sentryDsn = "";

    @CommandSuggestions(suggest = {"OFF", "FATAL", "ERROR", "WARN", "INFO", "DEBUG", "TRACE", "ALL"})
    @ConfigInfo(name = "log_level", comments = " Logs with a level higher than or equal to this level will be recorded.")
    public static String logLevel = "WARN";

    @ConfigInfo(name = "only_log_thrown", comments = " Only log with a Throwable will be recorded after enabling this.")
    public static boolean onlyLogThrown = true;

    @Override
    public void onLoaded(TomlConfigData configInstance, @Nullable Set<Exception> exs) {
        String sentryEnvironment = System.getenv("SENTRY_DSN");

        sentryDsn = sentryEnvironment != null && !sentryEnvironment.isBlank()
                ? sentryEnvironment
                : configInstance.contains("sentry.dsn") ? configInstance.get("sentry.dsn") : sentryDsn;

        logLevel = configInstance.contains("sentry.log-level") ? configInstance.get("sentry.log-level") : logLevel;
        onlyLogThrown = configInstance.contains("sentry.only-log-thrown") ? configInstance.get("sentry.only-log-thrown") : onlyLogThrown;

        if (sentryDsn != null && !sentryDsn.isBlank()) {
            SentryManager.init(Level.getLevel(logLevel));
        }
    }
}