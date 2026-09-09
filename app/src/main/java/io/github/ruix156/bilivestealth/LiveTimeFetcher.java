package io.github.ruix156.bilivestealth;

import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 直播间开播时间获取器 (场次 key 来源):
 * 请求 B 站公开接口 room/v1/Room/get_info 拿 live_time, 写入 Settings,
 * 供 "首进可见" 名单的每场次判定使用。失败不缓存, 下次进房自动重试。
 * (昵称获取已移至设置 App, 此处不再请求用户卡片接口。)
 */
public final class LiveTimeFetcher {
  private static final String TAG = "BiLiveStealth.LiveTime";
  private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
  /** roomId -> 上次获取时间 (elapsedRealtime), 30 分钟内不重复请求 */
  private static final Map<Long, Long> FETCH_TIME = new ConcurrentHashMap<>();
  private static final long REFRESH_INTERVAL = 30 * 60 * 1000L;

  private static final String UA =
      "Mozilla/5.0 (Linux; Android 12; Unspecified) AppleWebKit/537.36 BiliLiveStealth/1.3";

  private LiveTimeFetcher() {
  }

  /** 异步获取房间的开播时间 (成功后 30 分钟内不重复请求; 失败下次进房自动重试)。 */
  public static void fetchAsync(long roomId, Settings settings) {
    if (roomId <= 0 || settings == null) return;
    long now = SystemClock.elapsedRealtime();
    Long last = FETCH_TIME.get(roomId);
    if (last != null && now - last < REFRESH_INTERVAL) return;
    EXEC.execute(() -> {
      // 二次去重: 并发触发时仅首个任务真正请求
      Long prev = FETCH_TIME.get(roomId);
      if (prev != null && SystemClock.elapsedRealtime() - prev < REFRESH_INTERVAL) return;
      boolean ok = fetch(roomId, settings);
      if (ok) FETCH_TIME.put(roomId, SystemClock.elapsedRealtime());
    });
  }

  private static boolean fetch(long roomId, Settings settings) {
    try {
      // 实测可用: room/v1/Room/get_info 免 cookie 免签名 (getInfoByRoom 已被风控 -352, 弃用)
      String infoBody = getRaw(
          "https://api.live.bilibili.com/room/v1/Room/get_info?room_id=" + roomId);
      if (infoBody == null) {
        Log.w(TAG, "get_info failed (null body) room=" + roomId);
        return false;
      }
      JSONObject data = new JSONObject(infoBody).optJSONObject("data");
      if (data == null) {
        Log.w(TAG, "get_info no data room=" + roomId
            + " body=" + infoBody.substring(0, Math.min(160, infoBody.length())));
        return false;
      }
      String liveTime = data.optString("live_time", "");
      if (liveTime.isEmpty() || "0".equals(liveTime) || liveTime.startsWith("0000-00-00")) {
        long liveStart = data.optLong("live_start_time", 0);
        if (liveStart > 0) liveTime = String.valueOf(liveStart);
      }
      if (liveTime.isEmpty() || "0".equals(liveTime) || liveTime.startsWith("0000-00-00")) {
        Log.i(TAG, "get_info ok but not live yet room=" + roomId);
        return true; // 接口正常只是未开播, 不必反复打
      }
      settings.recordLiveTime(roomId, liveTime);
      Log.i(TAG, "live_time ok room=" + roomId + " live_time=" + liveTime);
      return true;
    } catch (Throwable t) {
      Log.w(TAG, "fetch error room=" + roomId, t);
      return false;
    }
  }

  /** GET 请求并返回原始响应体; 非 200 或异常时返回 null。 */
  private static String getRaw(String url) {
    HttpURLConnection conn = null;
    try {
      conn = (HttpURLConnection) new URL(url).openConnection();
      conn.setConnectTimeout(8000);
      conn.setReadTimeout(8000);
      conn.setRequestProperty("User-Agent", UA);
      conn.setRequestProperty("Referer", "https://live.bilibili.com/");
      int code = conn.getResponseCode();
      if (code != 200) {
        Log.w(TAG, "http " + code + " for " + url);
        return null;
      }
      StringBuilder sb = new StringBuilder();
      try (BufferedReader r = new BufferedReader(
          new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = r.readLine()) != null) {
          sb.append(line);
        }
      }
      return sb.toString();
    } catch (Throwable t) {
      Log.w(TAG, "request error " + url + " : " + t);
      return null;
    } finally {
      if (conn != null) {
        try {
          conn.disconnect();
        } catch (Throwable ignored) {
        }
      }
    }
  }
}
