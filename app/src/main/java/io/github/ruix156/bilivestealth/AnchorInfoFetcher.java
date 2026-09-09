package io.github.ruix156.bilivestealth;

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
  private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
  /** roomId -> 上次获取时间 (elapsedRealtime), 30 分钟内不重复请求 */
  private static final Map<Long, Long> FETCH_TIME = new ConcurrentHashMap<>();
  private static final long REFRESH_INTERVAL = 30 * 60 * 1000L;

  private static final String UA =
      "Mozilla/5.0 (Linux; Android 12; Unspecified) AppleWebKit/537.36 BiliLiveStealth/1.1";

  private AnchorInfoFetcher() {
  }

  /** 异步获取房间的主播信息 (同一房间 30 分钟内只请求一次)。 */
  public static void fetchAsync(long roomId, Settings settings) {
    if (roomId <= 0 || settings == null) return;
    long now = SystemClock.elapsedRealtime();
    Long last = FETCH_TIME.get(roomId);
    if (last != null && now - last < REFRESH_INTERVAL) return;
    FETCH_TIME.put(roomId, now);
    EXEC.execute(() -> fetch(roomId, settings));
  }

  private static void fetch(long roomId, Settings settings) {
    try {
      // 实测可用: room/v1/Room/get_info 免 cookie 免签名 (getInfoByRoom 已被风控 -352, 弃用)
      JSONObject data = getJson(
          "https://api.live.bilibili.com/room/v1/Room/get_info?room_id=" + roomId);
      if (data == null) return;
      long uid = data.optLong("uid", 0);
      if (uid <= 0) return;
      settings.recordAnchor(roomId, uid);
      String liveTime = data.optString("live_time", "");
      if (liveTime.isEmpty()) {
        long liveStart = data.optLong("live_start_time", 0);
        if (liveStart > 0) liveTime = String.valueOf(liveStart);
      }
      if (!liveTime.isEmpty()) settings.recordLiveTime(roomId, liveTime);

      // 主播昵称: 用户卡片接口
      JSONObject card = getJson(
          "https://api.bilibili.com/x/web-interface/card?mid=" + uid + "&photo=false");
      if (card != null) {
        JSONObject cardData = card.optJSONObject("data");
        JSONObject cardInfo = cardData == null ? null : cardData.optJSONObject("card");
        String name = cardInfo == null ? "" : cardInfo.optString("name", "");
        if (!name.isEmpty()) {
          settings.recordRoomName(roomId, name);
          settings.recordUname(uid, name);
        }
      }
    } catch (Throwable ignored) {
    }
  }

  /** GET 请求并返回 data 字段; code != 0 或异常时返回 null。 */
  private static JSONObject getJson(String url) {
    HttpURLConnection conn = null;
    try {
      conn = (HttpURLConnection) new URL(url).openConnection();
      conn.setConnectTimeout(8000);
      conn.setReadTimeout(8000);
      conn.setRequestProperty("User-Agent", UA);
      conn.setRequestProperty("Referer", "https://live.bilibili.com/");
      int code = conn.getResponseCode();
      if (code != 200) return null;
      StringBuilder sb = new StringBuilder();
      try (BufferedReader r = new BufferedReader(
          new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = r.readLine()) != null) {
          sb.append(line);
        }
      }
      JSONObject root = new JSONObject(sb.toString());
      if (root.optInt("code", -1) != 0) return null;
      return root.optJSONObject("data");
    } catch (Throwable t) {
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
