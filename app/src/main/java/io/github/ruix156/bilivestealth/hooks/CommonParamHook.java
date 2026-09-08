package io.github.ruix156.bilivestealth.hooks;

import static io.github.ruix156.bilivestealth.MainHook.appName;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.Map;

import io.github.ruix156.bilivestealth.MainHook;
import io.github.ruix156.bilivestealth.Settings;

import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * 拦截 B 站直播公共参数组装方法 addCommonParam(Map),
 * 按设置对进场上报 / 心跳 / 弹幕服务器请求做匿名化处理。
 */
public class CommonParamHook {
  private static final boolean DEBUG = false;

  private final MainHook mainHook;
  private final PackageReadyParam param;
  private final String addCommonParamClass;
  private final Settings settings;

  public CommonParamHook(MainHook mainHook, PackageReadyParam param, String addCommonParamClass, Settings settings) {
    this.mainHook = mainHook;
    this.param = param;
    this.addCommonParamClass = addCommonParamClass;
    this.settings = settings;
  }

  public void hook() throws Throwable {
    Class<?> clazz = Class.forName(addCommonParamClass, false, param.getClassLoader());
    Method method = clazz.getDeclaredMethod("addCommonParam", Map.class);
    mainHook.hook(method).intercept(chain -> {
      Object result = chain.proceed();
      try {
        Object arg0 = chain.getArg(0);
        if (arg0 instanceof Map) {
          // noinspection unchecked
          apply((Map<String, Object>) arg0);
        }
      } catch (Throwable t) {
        mainHook.log(Log.WARN, appName, param.getPackageName() + " apply common param failed", t);
      }
      return result;
    });
  }

  private void apply(Map<String, Object> params) {
    long roomId = parseRoomId(params.get("room_id"));
    if (roomId > 0) settings.setCurrentRoom(roomId);
    boolean stealth = settings.shouldStealth(roomId);

    // 获取弹幕服务器 (getDanmuInfo): 仅 "游客弹幕连接" 开启时移除 access_key。
    // 默认保留登录态, 否则 B 站会把弹幕昵称打码为 ***
    if (stealth && settings.guestDanmaku && params.containsKey("is_anchor")) {
      params.remove("access_key");
      debugLog("addCommonParam is_anchor access_key removed");
    }

    // 进场上报: 替换 room_id, 不触发进场特效/欢迎消息。
    // "首进可见"名单内的房间, 每场直播的首次入场保持正常, 之后隐身。
    if (stealth && settings.stealthEnter && params.containsKey("not_mock_enter_effect")) {
      if (settings.shouldFirstEntryBeVisible(roomId)) {
        settings.markFirstEntryDone(roomId);
        debugLog("addCommonParam enter report visible (first entry of session), room=" + roomId);
      } else {
        params.put("room_id", settings.fakeRoomId);
        debugLog("addCommonParam not_mock_enter_effect room_id replaced -> " + settings.fakeRoomId);
      }
    }

    // 房间心跳: 移除 access_key, 不计入在线人数/亲密度/高能榜
    if (stealth && settings.anonHeartbeat && (params.containsKey("hb") || params.containsKey("heart_beat"))) {
      params.remove("access_key");
      debugLog("addCommonParam heartbeat access_key removed");
    }
  }

  private static long parseRoomId(Object v) {
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
