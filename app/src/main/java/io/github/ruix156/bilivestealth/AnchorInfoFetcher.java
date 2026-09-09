package io.github.ruix156.bilivestealth;

import android.os.SystemClock;
import android.util.Log;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.ruix156.bilivestealth.Settings;

/**
 * 主播信息获取器:
 * 不依赖宿主 App 的 JSON 解析路径, 检测到房间号后自行请求 B 站公开接口,
 * 确定性地建立 房间 -> 主播 uid/昵称/开播时间 的映射。
 * 结果写入 Settings, 供名单按主播昵称显示与 "首进可见" 场次判定使用。
 */
public final class AnchorInfoFetcher {
  private static final String TAG = "AnchorInfoFetcher";
  private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
  /** roomId -> 上次获取时间 (elapsedRealtime), 30 分钟内不重复请求 */
  private static final Map<Long, Long> FETCH_TIME = new ConcurrentHashMap<>();
  private static final long REFRESH_INTERVAL = 30 * 60 * 1000L;

  private static final String UA =
      "Mozilla/5.0 (Linux; Android 12; Unspecified) AppleWebKit/537.36 BiliLiveStealth/1.1";

  private AnchorInfoFetcher() {
  }

  /** 异步获取房间的主播信息 (成功后 30 分钟内不重复请求; 失败下次进房自动重试)。 */
  public static void fetchAsync(long roomId, Settings settings) {
    if (roomId <= 0 || settings == null) return;
    long now = SystemClock.elapsedRealtime();
    Long last = FETCH_TIME.get(roomId);
    if (last != null && now - last < REFRESH_INTERVAL) return;
    EXEC.execute(() -> {
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
        Log.w(TAG, "get_info no data room=" + roomId + " body=" + infoBody.substring(0, Math.min(160, infoBody.length())));
        return false;
      }
      long uid = data.optLong("uid", 0);
      Log.i(TAG, "get_info ok room=" + roomId + " uid=" + uid
          + " live_time=" + data.opt("live_time"));
      if (uid <= 0) return false;
      settings.recordAnchor(roomId, uid);
      String liveTime = data.optString("live_time", "");
      if (liveTime.isEmpty() || "0".equals(liveTime) || liveTime.startsWith("0000-00-00")) {
        long liveStart = data.optLong("live_start_time", 0);
        if (liveStart > 0) liveTime = String.valueOf(liveStart);
      }
      if (!liveTime.isEmpty() && !"0".equals(liveTime) && !liveTime.startsWith("0000-00-00")) {
        settings.recordLiveTime(roomId, liveTime);
      }

      // 主播昵称: 用户卡片接口
      String cardBody = getRaw(
          "https://api.bilibili.com/x/web-interface/card?mid=" + uid + "&photo=false");
      if (cardBody == null) {
        Log.w(TAG, "card failed (null body) mid=" + uid);
        return false;
      }
      Log.i(TAG, "card body: " + cardBody.substring(0, Math.min(300, cardBody.length())));
      JSONObject card = new JSONObject(cardBody);
      if (card.optInt("code", -1) != 0) return false;
      JSONObject cardData = card.optJSONObject("data");
      JSONObject cardInfo = cardData == null ? null : cardData.optJSONObject("card");
      String name = cardInfo == null ? "" : cardInfo.optString("name", "");
      if (!name.isEmpty()) {
        settings.recordRoomName(roomId, name);
        settings.recordUname(uid, name);
        Log.i(TAG, "nickname ok room=" + roomId + " uid=" + uid + " name=" + name);
        return true;
      } else {
        Log.w(TAG, "card ok but name empty mid=" + uid);
        return false;
      }
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
