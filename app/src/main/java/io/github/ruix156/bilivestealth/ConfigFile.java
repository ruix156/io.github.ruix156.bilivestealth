package io.github.ruix156.bilivestealth;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 配置文件读写工具。
 * 配置由设置 App (模块自身) 写入 files/stealth_config.json, 并对文件和父目录
 * 开放 others 读/遍历权限, 供宿主进程读取; SELinux category 由 LSPosed/Vector
 * 对 xposedsharedprefs 模块的处理覆盖, 另在写入后尽力尝试 chcon 去除 MLS 类别 (静默失败)。
 */
public final class ConfigFile {
  private static final String TAG = "BiLiveStealth.Config";

  public static final String MODULE_PACKAGE = "io.github.ruix156.bilivestealth";
  public static final String FILE_NAME = "stealth_config.json";

  private ConfigFile() {
  }

  /** 模块端: 配置文件位置。 */
  public static File moduleFile(android.content.Context ctx) {
    return new File(ctx.getFilesDir(), FILE_NAME);
  }

  /** 读取配置; 文件不存在或解析失败返回 null。 */
  public static StealthConfig read(File f) {
    if (f == null || !f.isFile()) return null;
    FileInputStream fis = null;
    try {
      fis = new FileInputStream(f);
      StringBuilder sb = new StringBuilder();
      BufferedReader r = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8));
      String line;
      while ((line = r.readLine()) != null) sb.append(line);
      return StealthConfig.fromJson(sb.toString());
    } catch (Throwable t) {
      Log.w(TAG, "read config failed: " + t);
      return null;
    } finally {
      if (fis != null) {
        try {
          fis.close();
        } catch (Throwable ignored) {
        }
      }
    }
  }

  /** 模块端: 原子写配置 (tmp + rename) 并开放 others 读权限。 */
  public static void write(File f, StealthConfig c) throws Exception {
    File dir = f.getParentFile();
    if (dir != null && !dir.exists()) dir.mkdirs();
    File tmp = new File(dir, FILE_NAME + ".tmp");
    try (FileOutputStream fos = new FileOutputStream(tmp)) {
      fos.write(c.toJsonString().getBytes(StandardCharsets.UTF_8));
      fos.getFD().sync();
    }
    if (f.exists()) f.delete();
    if (!tmp.renameTo(f)) {
      tmp.delete();
      throw new Exception("rename failed");
    }
    ensureWorldReadable(f);
  }

  /** 开放配置文件 others 读权限 + 父目录链遍历权限, 并尝试修正 SELinux 类别。 */
  public static void ensureWorldReadable(File f) {
    try {
      File dir = f.getParentFile();
      if (dir != null) {
        dir.setExecutable(true, false);
        dir.setReadable(true, false);
        File dataDir = dir.getParentFile();
        if (dataDir != null) dataDir.setExecutable(true, false);
      }
      f.setReadable(true, false);
    } catch (Throwable t) {
      Log.w(TAG, "chmod failed: " + t);
    }
    chconRemoveCategory(f);
  }

  /**
   * 保存配置后, 用 root 把副本推送到宿主 files 目录:
   * Android 11+ 宿主进程受数据隔离限制无法直读模块目录, 推送到宿主私有目录后必然可读。
   * 需要 su 授权 (KernelSU/Magisk, 拒绝或无 root 时静默失败)。
   *
   * @return 是否至少成功同步到一个宿主
   */
  public static boolean pushToHost(android.content.Context ctx, File config) {
    String[] hosts = {"tv.danmaku.bili", "com.bilibili.app.in"};
    boolean any = false;
    for (String pkg : hosts) {
      Process p = null;
      try {
        long uid = ctx.createPackageContext(pkg,
            android.content.Context.CONTEXT_IGNORE_SECURITY).getApplicationInfo().uid;
        String dst = "/data/data/" + pkg + "/files/" + FILE_NAME;
        String cmd = "mkdir -p /data/data/" + pkg + "/files"
            + " && cp -f '" + config.getAbsolutePath() + "' '" + dst + "'"
            + " && chown " + uid + ":" + uid + " '" + dst + "'"
            + " && chmod 600 '" + dst + "'";
        p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
        if (p.waitFor() == 0) {
          any = true;
          Log.i(TAG, "config pushed to " + pkg);
        } else {
          Log.w(TAG, "push to " + pkg + " exit=" + p.exitValue());
        }
      } catch (Throwable t) {
        Log.w(TAG, "push to " + pkg + " failed: " + t);
      } finally {
        if (p != null) p.destroy();
      }
    }
    return any;
  }

  /**
   * 尽力尝试用 root 去掉文件与目录上的 SELinux MLS 类别 (category),
   * 使宿主 (不同 appId) 可读; 无 root 或未授权时静默失败, 由框架侧规则兜底。
   */
  private static void chconRemoveCategory(File f) {
    Process p = null;
    try {
      String ctx = "u:object_r:app_data_file:s0";
      p = Runtime.getRuntime().exec(new String[]{
          "su", "-c", "chcon", ctx, f.getParentFile().getParentFile().getAbsolutePath(),
          f.getParentFile().getAbsolutePath(), f.getAbsolutePath()});
      p.waitFor();
    } catch (Throwable ignored) {
    } finally {
      if (p != null) p.destroy();
    }
  }
}
