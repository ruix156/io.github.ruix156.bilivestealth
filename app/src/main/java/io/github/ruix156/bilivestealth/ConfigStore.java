package io.github.ruix156.bilivestealth;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;

/**
 * 宿主端配置读取器。
 * Android 11+ 宿主进程无法访问其它应用的 /data/data (mount namespace 数据隔离),
 * 因此设置 App 保存时会把配置副本推送到宿主自身 files 目录 (需 root, 一次性授权);
 * 无副本时尝试直读模块 files 目录 (低版本/框架特殊处理的场景)。
 * 以 mtime 变化检测 + 节流 (500ms) 热重载, volatile 引用替换保证线程安全。
 * 读取失败沿用旧配置; 从未读到则使用默认值 (默认隐身)。
 */
public final class ConfigStore {
  private static final String TAG = "BiLiveStealth.Config";
  private static final long RELOAD_INTERVAL_MS = 500;

  private final File[] candidates;
  private final long[] lastMods; // 每个候选文件独立记录 mtime, 避免不同文件间互相误判变化
  private volatile StealthConfig current = new StealthConfig();
  // 初始为 -RELOAD_INTERVAL_MS, 避免 MIN_VALUE 参与减法时 long 溢出导致永不加载
  private volatile long lastCheckAt = -RELOAD_INTERVAL_MS;

  public ConfigStore(Context hostContext) {
    File hostFile = null;
    try {
      hostFile = new File(hostContext.getFilesDir(), ConfigFile.FILE_NAME);
    } catch (Throwable ignored) {
    }
    File moduleFile = null;
    try {
      Context mctx = hostContext.createPackageContext(
          ConfigFile.MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
      moduleFile = new File(mctx.getFilesDir(), ConfigFile.FILE_NAME);
    } catch (Throwable ignored) {
    }
    if (hostFile != null && moduleFile != null) {
      candidates = new File[]{hostFile, moduleFile};
    } else if (hostFile != null) {
      candidates = new File[]{hostFile};
    } else {
      candidates = new File[]{new File(
          "/data/data/" + ConfigFile.MODULE_PACKAGE + "/files/" + ConfigFile.FILE_NAME)};
    }
    lastMods = new long[candidates.length];
    java.util.Arrays.fill(lastMods, Long.MIN_VALUE);
  }

  /** 关键决策点调用: 任一候选文件 mtime 变化时重载配置 (内部 500ms 节流)。 */
  public void maybeReload() {
    long now = SystemClock.elapsedRealtime();
    if (now - lastCheckAt < RELOAD_INTERVAL_MS) return;
    lastCheckAt = now;
    try {
      // 按文件独立比对 mtime: 任一候选变化即触发重载
      boolean changed = false;
      long[] cur = new long[candidates.length];
      for (int i = 0; i < candidates.length; i++) {
        cur[i] = candidates[i].lastModified(); // 不可见/不可访问时返回 0
        if (cur[i] != lastMods[i]) changed = true;
      }
      if (!changed) return;
      System.arraycopy(cur, 0, lastMods, 0, cur.length);

      StealthConfig c = null;
      String src = null;
      for (File f : candidates) {
        c = ConfigFile.read(f);
        if (c != null) {
          src = f.getAbsolutePath();
          break;
        }
      }
      if (c != null) {
        current = c;
        Log.i(TAG, "config loaded from " + src + " master=" + c.master
            + " mode=" + c.listMode + " black=" + c.blacklist.size()
            + " white=" + c.whitelist.size() + " first=" + c.firstVisibleRooms.size());
      } else {
        Log.w(TAG, "config unreadable, keep previous (host=" + candidates[0].getAbsolutePath()
            + " exists=" + candidates[0].exists() + ")");
      }
    } catch (Throwable t) {
      Log.w(TAG, "config reload failed: " + t);
    }
  }

  public StealthConfig get() {
    return current;
  }
}
