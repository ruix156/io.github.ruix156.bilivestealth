package io.github.ruix156.bilivestealth.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.ruix156.bilivestealth.ConfigFile;
import io.github.ruix156.bilivestealth.LiveTimeFetcher;
import io.github.ruix156.bilivestealth.Settings;
import io.github.ruix156.bilivestealth.StealthConfig;

/**
 * 模块独立设置页 (桌面图标进入, 仿哔哩漫游式独立配置)。
 * 程序化暗色 UI, 无 XML 布局; 所有修改即时写入模块配置文件
 * (files/stealth_config.json, 世界可读), 宿主 B 站按 mtime 热读取。
 * 名单通过手动输入房间号维护, 添加后自动请求 B 站公开接口补全主播昵称。
 */
public class SettingsActivity extends Activity {
  private static final String TAG = "BiLiveStealth.Settings";

  private static final int PANEL_COLOR = 0xFF141414;
  private static final int BTN_COLOR = 0xFF2E2E2E;
  private static final int BTN_DEL_COLOR = 0xFF5A2A2A;
  private static final int INPUT_COLOR = 0xFF1E1E1E;
  private static final int COLOR_TEXT = 0xFFE0E0E0;
  private static final int COLOR_TEXT_DIM = 0xFF9E9E9E;
  private static final int COLOR_MODE_ON = 0xFF7CB342;
  private static final int COLOR_MODE_OFF = 0xFFBDBDBD;

  private final ExecutorService exec = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  /** 拉取昵称中的房间号, 防止重复请求 */
  private final Set<Long> fetchingNames = ConcurrentHashMap.newKeySet();

  private StealthConfig config = new StealthConfig();
  /** UI 回填期间抑制控件监听 */
  private boolean applying = false;

  private LinearLayout listContainer;
  private Button blackModeBtn;
  private Button whiteModeBtn;
  private EditText fakeRoomInput;
  private TextView statusView;
  private String versionName = "";

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    try {
      versionName = getPackageManager()
          .getPackageInfo(getPackageName(), 0).versionName;
    } catch (Exception ignored) {
    }

    exec.execute(() -> {
      StealthConfig c = ConfigFile.read(ConfigFile.moduleFile(this));
      if (c != null) config = c;
      main.post(this::refreshAll);
    });
    exec.execute(this::updateStatus);

    setContentView(buildUi());
    refreshAll();
  }

  // ===== 界面构建 =====

  private ScrollView buildUi() {
    ScrollView scroll = new ScrollView(this);
    scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
    scroll.setFillViewport(true);

    LinearLayout panel = new LinearLayout(this);
    panel.setOrientation(LinearLayout.VERTICAL);
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(PANEL_COLOR);
    panel.setBackground(bg);
    int pad = dp(16);
    panel.setPadding(pad, pad, pad, pad);
    scroll.addView(panel, new ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    // -- 标题 --
    TextView title = text("B站直播隐身观看", 20, true);
    panel.addView(title);
    TextView subtitle = text("独立设置版 v" + versionName + " · 无需进直播间配置", 12, false);
    subtitle.setTextColor(COLOR_TEXT_DIM);
    subtitle.setPadding(0, dp(2), 0, dp(6));
    panel.addView(subtitle);

    statusView = text("", 11, false);
    statusView.setTextColor(COLOR_TEXT_DIM);
    statusView.setPadding(0, 0, 0, dp(4));
    panel.addView(statusView);

    TextView hint = text("使用方法: 在 LSPosed/Vector 中启用本模块并勾选 B 站作用域, "
        + "强停 B 站后重新进入直播间生效。默认隐身: 黑名单模式下全部直播间隐身。",
        12, false);
    hint.setTextColor(COLOR_TEXT_DIM);
    panel.addView(hint);

    // -- 功能开关 --
    panel.addView(sectionLabel("功能开关"));
    panel.addView(sw("总开关 (隐身功能)", () -> config.master, v -> config.master = v));
    panel.addView(sw("隐身进场 (不触发进场特效/欢迎)", () -> config.stealthEnter, v -> config.stealthEnter = v));
    panel.addView(sw("匿名心跳 (不计入在线/高能榜)", () -> config.anonHeartbeat, v -> config.anonHeartbeat = v));
    panel.addView(sw("游客弹幕连接 (会致昵称打码,慎开)", () -> config.guestDanmaku, v -> config.guestDanmaku = v));
    panel.addView(sw("过滤进场消息 (本地·实验)", () -> config.filterEntryMsg, v -> config.filterEntryMsg = v));

    // -- 名单模式 --
    panel.addView(sectionLabel("名单模式"));
    LinearLayout modeRow = row();
    blackModeBtn = btn("黑名单 (全部隐身,此列表除外)", v -> {
      config.listMode = StealthConfig.MODE_BLACK;
      save();
      refreshAll();
    });
    whiteModeBtn = btn("白名单 (仅此列表隐身)", v -> {
      config.listMode = StealthConfig.MODE_WHITE;
      save();
      refreshAll();
    });
    modeRow.addView(blackModeBtn);
    modeRow.addView(whiteModeBtn);
    panel.addView(modeRow);

    // -- 名单管理 --
    panel.addView(sectionLabel("名单管理 (手动输入房间号)"));
    listContainer = new LinearLayout(this);
    listContainer.setOrientation(LinearLayout.VERTICAL);
    panel.addView(listContainer);

    // -- 伪装房间号 --
    panel.addView(sectionLabel("进场上报伪装房间号"));
    LinearLayout fakeRow = row();
    fakeRoomInput = input();
    fakeRow.addView(fakeRoomInput, new LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    fakeRow.addView(btn("保存", v -> {
      String s = fakeRoomInput.getText().toString().trim();
      if (s.matches("\\d{3,}")) {
        config.fakeRoomId = s;
        save();
        toast("伪装房间号已保存");
      } else {
        toast("房间号无效");
      }
    }));
    panel.addView(fakeRow);

    // -- 底部说明 --
    TextView footer = text("保存时自动将配置同步到 B 站 (需 root 授权一次)\n"
        + "首进可见: 该房间每场直播首次入场可见, 之后隐身\n"
        + "昵称自动从 B 站公开接口获取, 仅用于名单显示", 11, false);
    footer.setTextColor(COLOR_TEXT_DIM);
    footer.setPadding(0, dp(16), 0, dp(8));
    panel.addView(footer);
    return scroll;
  }

  /** 重建三个名单分区 (含各自的添加输入行)。 */
  private void refreshLists() {
    if (listContainer == null) return;
    listContainer.removeAllViews();
    addListSection("黑名单 (全部隐身, 此列表房间正常观看)",
        config.blacklist, StealthConfig.MODE_BLACK);
    addListSection("白名单 (仅此列表房间隐身)",
        config.whitelist, StealthConfig.MODE_WHITE);
    addListSection("首进可见 (每场直播首次入场不隐身)",
        config.firstVisibleRooms, "first");
  }

  private void addListSection(String title, Set<Long> rooms, String which) {
    TextView header = text(title, 12, true);
    header.setTextColor(COLOR_TEXT_DIM);
    LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    hlp.topMargin = dp(10);
    hlp.bottomMargin = dp(2);
    header.setLayoutParams(hlp);
    listContainer.addView(header);

    // 添加行
    LinearLayout addRow = row();
    EditText input = input();
    input.setHint("输入房间号");
    addRow.addView(input, new LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    addRow.addView(btn("添加", v -> {
      String s = input.getText().toString().trim();
      long roomId;
      try {
        roomId = Long.parseLong(s);
      } catch (Exception e) {
        roomId = 0;
      }
      if (roomId <= 0) {
        toast("请输入有效房间号");
        return;
      }
      if (!rooms.add(roomId)) {
        toast("已在名单中");
        return;
      }
      save();
      refreshLists();
      toast("已添加 " + roomId);
      fetchNameAsync(roomId);
    }));
    listContainer.addView(addRow);

    if (rooms.isEmpty()) {
      TextView none = text("无", 12, false);
      none.setTextColor(COLOR_TEXT_DIM);
      none.setPadding(dp(8), 0, 0, 0);
      listContainer.addView(none);
      return;
    }

    List<Long> sorted = config.sortedList(rooms);
    for (Long roomId : sorted) {
      LinearLayout entry = row();
      String display = config.displayName(roomId) + " (" + roomId + ")";
      TextView name = text(display, 13, false);
      name.setLayoutParams(new LinearLayout.LayoutParams(
          0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
      entry.addView(name);
      entry.addView(btnDel("删", v -> {
        rooms.remove(roomId);
        config.roomNames.remove(roomId);
        save();
        refreshLists();
        toast("已移除 " + display);
      }));
      listContainer.addView(entry);
    }
  }

  private void refreshAll() {
    applying = true;
    applySwitches();
    if (blackModeBtn != null) {
      boolean black = StealthConfig.MODE_BLACK.equals(config.listMode);
      blackModeBtn.setTextColor(black ? COLOR_MODE_ON : COLOR_MODE_OFF);
      whiteModeBtn.setTextColor(black ? COLOR_MODE_OFF : COLOR_MODE_ON);
    }
    if (fakeRoomInput != null && !fakeRoomInput.hasFocus()
        && !config.fakeRoomId.equals(fakeRoomInput.getText().toString())) {
      fakeRoomInput.setText(config.fakeRoomId);
    }
    refreshLists();
    applying = false;
  }

  /** 回填开关状态 (通过访问器读写 StealthConfig 字段)。 */
  private void applySwitches() {
    // 交给 sw() 注册的回填任务在 buildUi 时执行; 此处按需重建开关状态
    for (Runnable r : switchRefill) r.run();
  }

  /** 开关回填任务 (buildUi 阶段填充)。 */
  private final java.util.List<Runnable> switchRefill = new java.util.ArrayList<>();

  // ===== 配置读写 =====

  private void save() {
    StealthConfig snapshot = config.copy();
    exec.execute(() -> {
      try {
        java.io.File f = ConfigFile.moduleFile(SettingsActivity.this);
        ConfigFile.write(f, snapshot);
        boolean pushed = ConfigFile.pushToHost(SettingsActivity.this, f);
        main.post(() -> setStatus(pushed
            ? "已保存并同步到 B 站 (" + nowTime() + ")"
            : "已保存; 同步 B 站失败(需root授权), B站内暂不生效"));
      } catch (Exception e) {
        Log.w(TAG, "save failed", e);
        main.post(() -> {
          setStatus("保存失败: " + e.getMessage());
          toast("保存失败: " + e.getMessage());
        });
      }
    });
  }

  private void updateStatus() {
    java.io.File f = ConfigFile.moduleFile(this);
    main.post(() -> setStatus(f.isFile() ? "配置文件已存在" : "尚未生成配置 (改动后自动创建)"));
  }

  private void setStatus(String s) {
    if (statusView != null) {
      statusView.setText("配置状态: " + s);
    }
  }

  private static String nowTime() {
    return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        .format(new java.util.Date());
  }

  // ===== 昵称获取 (B 站公开接口) =====

  /** 异步获取主播昵称: room/v1/Room/get_info -> uid -> x/web-interface/card -> name。 */
  private void fetchNameAsync(long roomId) {
    if (!fetchingNames.add(roomId)) return;
    exec.execute(() -> {
      String name = fetchName(roomId);
      fetchingNames.remove(roomId);
      if (name != null) {
        main.post(() -> {
          config.roomNames.put(roomId, name);
          save();
          refreshLists();
        });
      }
    });
  }

  private String fetchName(long roomId) {
    try {
      String infoBody = httpGet(
          "https://api.live.bilibili.com/room/v1/Room/get_info?room_id=" + roomId);
      if (infoBody == null) return null;
      JSONObject data = new JSONObject(infoBody).optJSONObject("data");
      if (data == null) return null;
      long uid = data.optLong("uid", 0);
      if (uid <= 0) return null;
      String cardBody = httpGet(
          "https://api.bilibili.com/x/web-interface/card?mid=" + uid + "&photo=false");
      if (cardBody == null) return null;
      JSONObject card = new JSONObject(cardBody);
      if (card.optInt("code", -1) != 0) return null;
      JSONObject cardData = card.optJSONObject("data");
      JSONObject cardInfo = cardData == null ? null : cardData.optJSONObject("card");
      String name = cardInfo == null ? "" : cardInfo.optString("name", "");
      return name.isEmpty() ? null : name;
    } catch (Throwable t) {
      Log.w(TAG, "fetchName room=" + roomId + " : " + t);
      return null;
    }
  }

  private static String httpGet(String url) {
    HttpURLConnection conn = null;
    try {
      conn = (HttpURLConnection) new URL(url).openConnection();
      conn.setConnectTimeout(8000);
      conn.setReadTimeout(8000);
      conn.setRequestProperty("User-Agent",
          "Mozilla/5.0 (Linux; Android 12) BiliLiveStealth/1.3");
      conn.setRequestProperty("Referer", "https://live.bilibili.com/");
      int code = conn.getResponseCode();
      if (code != 200) return null;
      StringBuilder sb = new StringBuilder();
      try (BufferedReader r = new BufferedReader(
          new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
      }
      return sb.toString();
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

  // ===== UI 工具 =====

  /** 生成一个开关; getter/setter 直接映射到配置字段, 回填经 switchRefill。 */
  private LinearLayout sw(String label, java.util.function.Supplier<Boolean> getter,
      java.util.function.Consumer<Boolean> setter) {
    Switch s = new Switch(this);
    s.setText(label);
    s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
    s.setTextColor(COLOR_TEXT);
    s.setChecked(getter.get());
    s.setPadding(0, dp(8), 0, dp(8));
    s.setOnCheckedChangeListener((b, on) -> {
      if (applying) return;
      setter.accept(on);
      save();
    });
    switchRefill.add(() -> {
      boolean prev = applying;
      applying = true;
      s.setChecked(getter.get());
      applying = prev;
    });
    LinearLayout wrap = new LinearLayout(this);
    wrap.setOrientation(LinearLayout.VERTICAL);
    wrap.addView(s);
    return wrap;
  }

  private LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.topMargin = dp(8);
    lp.bottomMargin = dp(4);
    l.setLayoutParams(lp);
    return l;
  }

  private TextView sectionLabel(String s) {
    TextView tv = text(s, 13, true);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.topMargin = dp(14);
    lp.bottomMargin = dp(2);
    tv.setLayoutParams(lp);
    return tv;
  }

  private TextView text(String s, int sp, boolean bold) {
    TextView tv = new TextView(this);
    tv.setText(s);
    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    tv.setTextColor(COLOR_TEXT);
    if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
    return tv;
  }

  private EditText input() {
    EditText e = new EditText(this);
    e.setInputType(InputType.TYPE_CLASS_NUMBER);
    e.setSingleLine(true);
    e.setImeOptions(EditorInfo.IME_ACTION_DONE);
    e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
    e.setTextColor(COLOR_TEXT);
    e.setHintTextColor(0xFF8A8A8A);
    GradientDrawable bg = new GradientDrawable();
    bg.setCornerRadius(dp(8));
    bg.setColor(INPUT_COLOR);
    e.setBackground(bg);
    e.setPadding(dp(10), dp(6), dp(10), dp(6));
    return e;
  }

  private Button btn(String label, View.OnClickListener listener) {
    return btn(label, BTN_COLOR, listener);
  }

  private Button btnDel(String label, View.OnClickListener listener) {
    return btn(label, BTN_DEL_COLOR, listener);
  }

  private Button btn(String label, int bgColor, View.OnClickListener listener) {
    Button b = new Button(this);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
    b.setTextColor(COLOR_TEXT);
    b.setMinHeight(0);
    b.setMinimumHeight(0);
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setPadding(dp(10), dp(5), dp(10), dp(5));
    GradientDrawable bg = new GradientDrawable();
    bg.setCornerRadius(dp(8));
    bg.setColor(bgColor);
    b.setBackground(bg);
    b.setOnClickListener(listener);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.leftMargin = dp(4);
    lp.rightMargin = dp(4);
    b.setLayoutParams(lp);
    return b;
  }

  private void toast(String s) {
    Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
  }

  private int dp(int v) {
    return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
        getResources().getDisplayMetrics());
  }
}
