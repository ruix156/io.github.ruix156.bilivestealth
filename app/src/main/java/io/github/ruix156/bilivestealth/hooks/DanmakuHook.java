package io.github.ruix156.bilivestealth.hooks;

import static io.github.ruix156.bilivestealth.MainHook.appName;

import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ruix156.bilivestealth.MainHook;
import io.github.ruix156.bilivestealth.Settings;

import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * 弹幕相关 hook:
 * 1. websocket 认证包 uid 处理 (默认保持真实登录态, 修复昵称被打码为 *** 的问题)
 * 2. 本地过滤进场消息/特效 (可选, 实验性)
 * 3. 嗅探房间的开播时间 (场次 key, 供 "首进可见" 每场次判定):
 *    - WS 消息: 单参 parseObject/parse 入口
 *    - HTTP 响应: 带 Type 的 parseObject 重载入口, 进房间即可获得场次, 无需等弹幕
 */
public class DanmakuHook {
  private static final boolean DEBUG = false;
  private static final String DROP_CMD = "__bili_stealth_drop__";

  // 需要本地过滤的进场相关消息 cmd
  private static final Set<String> FILTER_CMDS = new HashSet<>(Arrays.asList(
      "INTERACT_WORD", "INTERACT_WORD_V2", "WELCOME", "WELCOME_GUARD", "ENTRY_EFFECT", "LOG_IN_NOTICE"));

  private final MainHook mainHook;
  private final PackageReadyParam param;
  private final Settings settings;

  /** JSON.parseObject(String) 的 Method 引用, 供 HTTP 响应文本嗅探时自解析。 */
  private volatile Method singleParseMethod;

  public DanmakuHook(MainHook mainHook, PackageReadyParam param, Settings settings) {
    this.mainHook = mainHook;
    this.param = param;
    this.settings = settings;
  }

  public void hook() throws Throwable {
    authPacket();
    parseHook();
  }

  // 弹幕服务器 websocket 认证包
  // 默认保持真实 uid (登录态连接), 避免 B 站将弹幕发送者昵称打码为 ***
  // 仅当开启 "游客弹幕连接" 时才将 uid 置 0 (匿名)
  private void authPacket() throws Throwable {
    Class<?> clazz = Class.forName("com.alibaba.fastjson.JSONObject", false, param.getClassLoader());
    Method method = clazz.getDeclaredMethod("put", String.class, Object.class);
    mainHook.hook(method).intercept(chain -> {
      if (!settings.configSnapshot().guestDanmaku) return chain.proceed();
      if ("uid".equals(chain.getArg(0)) && ((Map<String, ?>) chain.getThisObject()).containsKey("group")) {
        Object[] args = chain.getArgs().toArray();
        args[1] = 0;
        debugLog("fastjson uid -> 0 (guest danmaku)");
        return chain.proceed(args);
      }
      return chain.proceed();
    });
  }

  // 解析入口 hook: 单参 (WS 消息) + 带 Type 重载 (HTTP 响应)
  private void parseHook() throws Throwable {
    Class<?> clazz = Class.forName("com.alibaba.fastjson.JSON", false, param.getClassLoader());

    // 1) 单参入口: JSON.parseObject(String) / JSON.parse(String) — 弹幕 WS 消息
    for (String name : new String[]{"parseObject", "parse"}) {
      try {
        Method method = clazz.getDeclaredMethod(name, String.class);
        if ("parseObject".equals(name)) {
          singleParseMethod = method;
        }
        mainHook.hook(method).intercept(chain -> {
          Object result = chain.proceed();
          try {
            if (result instanceof Map) {
              Map<?, ?> m = (Map<?, ?>) result;
              if (settings.configSnapshot().filterEntryMsg) {
                filterEntryCmd(m);
              }
              scanRoomLiveTime(m);
            }
          } catch (Throwable t) {
            mainHook.log(Log.WARN, appName, param.getPackageName() + " parse hook failed", t);
          }
          return result;
        });
      } catch (NoSuchMethodException ignored) {
      }
    }

    // 2) 带 Type 重载: parseObject(String, Type/Class, ...) — HTTP 响应解析成 Bean,
    //    响应文本含 room_id 时 (房间信息等) 先自解析一遍做嗅探, 进房间立即获得开播时间
    for (Method m : clazz.getDeclaredMethods()) {
      if (!"parseObject".equals(m.getName())) continue;
      Class<?>[] ps = m.getParameterTypes();
      if (ps.length >= 2 && ps[0] == String.class && (ps[1] == Type.class || ps[1] == Class.class)) {
        mainHook.hook(m).intercept(chain -> {
          try {
            Object text = chain.getArg(0);
            if (text instanceof String && ((String) text).contains("room_id")) {
              sniffText((String) text);
            }
          } catch (Throwable ignored) {
          }
          return chain.proceed();
        });
      }
    }
  }

  /** 对响应文本自行解析并嗅探 (借用已被 hook 的单参 parseObject, 嗅探逻辑自动复用)。 */
  private void sniffText(String text) {
    try {
      Method m = singleParseMethod;
      if (m == null) return;
      m.invoke(null, text);
    } catch (Throwable ignored) {
    }
  }

  /** 将进场类消息的 cmd 改写为无效值, 使 UI 不展示。 */
  private void filterEntryCmd(Map<?, ?> m) {
    Object cmd = m.get("cmd");
    if (cmd instanceof String && FILTER_CMDS.contains(cmd)) {
      debugLog("entry message filtered: " + cmd);
      // noinspection unchecked
      ((Map<String, Object>) m).put("cmd", DROP_CMD);
    }
  }

  /**
   * 递归嗅探解析结果 (顶层 / data / data 的子对象) 中的 room_id + live_time -> 开播时间 (场次 key)。
   */
  private void scanRoomLiveTime(Map<?, ?> map) {
    scanLiveTime(map);
    Object data = map.get("data");
    if (!(data instanceof Map)) return;
    Map<?, ?> d = (Map<?, ?>) data;
    scanLiveTime(d);
    for (Object v : d.values()) {
      if (v instanceof Map) {
        scanLiveTime((Map<?, ?>) v);
      }
    }
  }

  /** 从扁平 Map 中学习房间开播时间。 */
  private void scanLiveTime(Map<?, ?> m) {
    if (!m.containsKey("room_id")) return;
    Object liveTime = m.get("live_time");
    if (liveTime != null) {
      settings.recordLiveTime(parseLong(m.get("room_id")), String.valueOf(liveTime));
    }
  }

  private static long parseLong(Object v) {
    if (v == null) return 0L;
    if (v instanceof Number) return ((Number) v).longValue();
    try {
      return Long.parseLong(v.toString().trim());
    } catch (Exception e) {
      return 0L;
    }
  }

  // debug日志
  public void debugLog(String log) {
    if (DEBUG) {
      mainHook.log(Log.INFO, appName, param.getPackageName() + " " + log);
    }
  }
}
