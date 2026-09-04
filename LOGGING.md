# Plugin Core logging

Plugin Core provides one file-only logger to every dependent plugin. Logs are not sent to Minecraft chat, the server console, or Discord. The only console fallback is a write-failure warning when the log file itself cannot be written.

## Administration

- `/plugincore debug on` enables debug entries for every connected plugin and persists the setting.
- `/plugincore debug off` disables debug entries globally.
- `/plugincore debug status` shows the current setting.
- `/plugincore logs` shows the absolute log directory and its plugin folders.
- `/plugincore logs <plugin>` shows that plugin's daily files.

Files are written to `plugins/plugincore/logs/<plugin>/YYYY-MM-DD.log`. They rotate daily and are retained for 30 days by default. Change `logging.retention-days` in `config.yml` and run `/plugincore reloadcore` to apply it. On POSIX filesystems the directories and files are restricted to the server account.

## Plugin API

Add `depend: [plugincore]` to `plugin.yml`, compile against `plugincore-api.jar`, and obtain a logger during `onEnable`:

```java
private PluginLogger log;

@Override
public void onEnable() {
    log = PluginLogs.get(this);
    log.info("My plugin enabled.");
    log.debug(() -> "Expensive state: " + collectState());
}
```

Available methods are `debug`, `info`, `warn`, and `error`. Warning and error methods accept an optional `Throwable` and include its complete stack trace. Do not log passwords, access tokens, authentication codes, or message contents that may contain private data.
