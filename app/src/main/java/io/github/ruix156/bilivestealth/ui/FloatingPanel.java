package io.github.ruix156.bilivestealth.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import io.github.ruix156.bilivestealth.MainHook;
import io.github.ruix156.bilivestealth.Settings;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * 直播间内悬浮实时开关面板。
 *
 * <p>通过 hook Activity.onResume, 在类名含 "liveroom" 的直播间 Activity 的
 * DecorView 上挂载一个可拖动的悬浮球; 点击悬浮球展开设置面板。
 * 面板强制使用暗色主题 (避免跟随宿主亮色主题导致白底白字)。
 * 所有修改立即生效并持久化 (存于 B 站应用自身 SharedPreferences)。
 */
public class FloatingPanel {
  private static final String TAG_BALL = "bilivestealth_ball";
  private static final String TAG_PANEL = "bilivestealth_panel";

  private static final int BALL_COLOR = 0xB31B1B1B;
  private static final int PANEL_COLOR = 0xF20F0F0F;
  private static final int BTN_COLOR = 0xFF2E2E2E;
  private static final int INPUT_COLOR = 0xFF1E1E1E;
  private static final int COLOR_TEXT = 0xFFE0E0E0;
  private static final int COLOR_TEXT_DIM = 0xFF9E9E9E;
  private static final int COLOR_MODE_ON = 0xFF7CB342;
  private static final int COLOR_MODE_OFF = 0xFFBDBDBD;

  private final MainHook mainHook;
  private final PackageReadyParam param;
  private final Settings settings;

  /** 刷新面板动态显示 (房间号/名单/按钮高亮) 的回调, 由 createPanel 构建完成后赋值。 */
  private Runnable refresh = () -> {
  };

  public FloatingPanel(MainHook mainHook, PackageReadyParam param, Settings settings) {
    this.mainHook = mainHook;
    this.param = param;
    this.settings = settings;
  }

  /** hook Activity.onResume, 在直播间 Activity 上挂载悬浮球与设置面板。 */
  public void install() {
    try {
      Method onResume = Activity.class.getDeclaredMethod("onResume");
      mainHook.hook(onResume).intercept(chain -> {
        Object result = chain.proceed();
        try {
          Object thiz = chain.getThisObject();
          if (thiz instanceof Activity) {
            attach((Activity) thiz);
          }
        } catch (Throwable t) {
          mainHook.log(Log.WARN, MainHook.appName,
              param.getPackageName() + " floating panel attach failed", t);
        }
        return result;
      });
    } catch (Throwable t) {
      mainHook.log(Log.ERROR, MainHook.appName,
          param.getPackageName() + " hook Activity.onResume failed", t);
    }
  }

  /** 仅在直播间 Activity (类名含 liveroom) 上挂载, 每个 Activity 实例只挂载一次。 */
  private void attach(Activity activity) {
    String className = activity.getClass().getName().toLowerCase();
    if (!className.contains("liveroom")) return;

    ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
    if (decor == null || decor.findViewWithTag(TAG_BALL) != null) return;

    // 强制暗色主题, 避免宿主亮色主题下控件白底白字
    Context ctx = new ContextThemeWrapper(activity, android.R.style.Theme_Material);

    createBall(ctx, decor);
    createPanel(ctx, decor);
  }

  // ===== 悬浮球 =====

  private void createBall(Context ctx, ViewGroup decor) {
    FrameLayout ball = new FrameLayout(ctx);
    ball.setTag(TAG_BALL);
    GradientDrawable ballBg = new GradientDrawable();
    ballBg.setShape(GradientDrawable.OVAL);
    ballBg.setColor(BALL_COLOR);
    ball.setBackground(ballBg);

    TextView label = new TextView(ctx);
    label.setText("隐");
    label.setTextColor(0xFFFFFFFF);
    label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
    label.setGravity(Gravity.CENTER);
    ball.addView(label, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    FrameLayout.LayoutParams ballLp = new FrameLayout.LayoutParams(dp(ctx, 42), dp(ctx, 42));
    ballLp.gravity = Gravity.TOP | Gravity.END;
    ballLp.topMargin = dp(ctx, 280);
    ballLp.rightMargin = dp(ctx, 6);
    ball.setLayoutParams(ballLp);

    final float[] down = new float[2];
    final float[] base = new float[2];
    final boolean[] moved = {false};
    final int touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();

    // 按下拖动改变位置; 未超过 touchSlop 判定为点击, 切换面板显示
    ball.setOnTouchListener((v, ev) -> {
      switch (ev.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          down[0] = ev.getRawX();
          down[1] = ev.getRawY();
          base[0] = ball.getX();
          base[1] = ball.getY();
          moved[0] = false;
          return true;
        case MotionEvent.ACTION_MOVE: {
          float dx = ev.getRawX() - down[0];
          float dy = ev.getRawY() - down[1];
          if (!moved[0] && Math.hypot(dx, dy) > touchSlop) moved[0] = true;
          if (moved[0]) {
            float x = Math.max(0, Math.min(base[0] + dx, decor.getWidth() - ball.getWidth()));
            float y = Math.max(dp(ctx, 40),
                Math.min(base[1] + dy, decor.getHeight() - ball.getHeight()));
            ball.setX(x);
            ball.setY(y);
          }
          return true;
        }
        case MotionEvent.ACTION_UP:
          if (!moved[0]) togglePanel(decor);
          return true;
        default:
          return false;
      }
    });
    decor.addView(ball);
  }

  // ===== 设置面板 =====

  private void createPanel(Context ctx, ViewGroup decor) {
    ScrollView scroll = new ScrollView(ctx);
    scroll.setTag(TAG_PANEL);
    scroll.setVisibility(View.GONE);
    scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

    LinearLayout panel = new LinearLayout(ctx);
    panel.setOrientation(LinearLayout.VERTICAL);
    GradientDrawable cardBg = new GradientDrawable();
    cardBg.setCornerRadius(dp(ctx, 14));
    cardBg.setColor(PANEL_COLOR);
    panel.setBackground(cardBg);
    int pad = dp(ctx, 14);
    panel.setPadding(pad, pad, pad, pad);
    scroll.addView(panel, new ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    FrameLayout.LayoutParams scrollLp = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    scrollLp.leftMargin = dp(ctx, 14);
    scrollLp.rightMargin = dp(ctx, 14);
    scrollLp.topMargin = dp(ctx, 56);
    scrollLp.bottomMargin = dp(ctx, 36);
    scroll.setLayoutParams(scrollLp);

    // -- 标题行 --
    LinearLayout titleRow = row(ctx);
    TextView roomLabel = text(ctx, "", 12, false);
    roomLabel.setTextColor(COLOR_TEXT_DIM);
    LinearLayout.LayoutParams roomLp = new LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    roomLp.leftMargin = dp(ctx, 10);
    roomLabel.setLayoutParams(roomLp);
    roomLabel.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    titleRow.addView(text(ctx, "直播隐身设置", 16, true));
    titleRow.addView(roomLabel);
    titleRow.addView(btn(ctx, "收起", v -> scroll.setVisibility(View.GONE)));
    panel.addView(titleRow);

    // -- 功能开关 --
    panel.addView(sw(ctx, "总开关 (隐身功能)", settings.master,
        (b, on) -> settings.setMaster(on)));
    panel.addView(sw(ctx, "隐身进场 (不触发进场特效/欢迎)", settings.stealthEnter,
        (b, on) -> settings.setStealthEnter(on)));
    panel.addView(sw(ctx, "匿名心跳 (不计入在线/高能榜)", settings.anonHeartbeat,
        (b, on) -> settings.setAnonHeartbeat(on)));
    panel.addView(sw(ctx, "游客弹幕连接 (会致昵称打码,慎开)", settings.guestDanmaku,
        (b, on) -> settings.setGuestDanmaku(on)));
    panel.addView(sw(ctx, "过滤进场消息 (本地·实验)", settings.filterEntryMsg,
        (b, on) -> settings.setFilterEntryMsg(on)));

    // -- 名单模式 --
    panel.addView(sectionLabel(ctx, "名单模式"));
    LinearLayout modeRow = row(ctx);
    Button blackBtn = btn(ctx, "黑名单 (此列表房间不隐身)", v -> {
      settings.setListMode(Settings.MODE_BLACK);
      refresh.run();
    });
    Button whiteBtn = btn(ctx, "白名单 (仅此列表房间隐身)", v -> {
      settings.setListMode(Settings.MODE_WHITE);
      refresh.run();
    });
    modeRow.addView(blackBtn);
    modeRow.addView(whiteBtn);
    panel.addView(modeRow);

    // -- 当前房间 --
    LinearLayout roomRow = row(ctx);
    TextView curRoom = text(ctx, "当前房间: -", 13, false);
    curRoom.setLayoutParams(new LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    roomRow.addView(curRoom);
    roomRow.addView(btn(ctx, "+白名单", v -> {
      settings.addRoom(settings.getCurrentRoom(), true);
      refresh.run();
      toast(ctx, "已加入白名单");
    }));
    roomRow.addView(btn(ctx, "+黑名单", v -> {
      settings.addRoom(settings.getCurrentRoom(), false);
      refresh.run();
      toast(ctx, "已加入黑名单");
    }));
    roomRow.addView(btn(ctx, "移出名单", v -> {
      settings.removeRoom(settings.getCurrentRoom());
      refresh.run();
      toast(ctx, "已移出全部名单");
    }));
    panel.addView(roomRow);

    // -- 指定直播间: 每场首进可见 --
    panel.addView(sectionLabel(ctx, "指定直播间 (每场直播首次入场不隐身)"));
    LinearLayout firstRow = row(ctx);
    TextView firstHint = text(ctx, "仅对该列表房间生效", 11, false);
    firstHint.setTextColor(COLOR_TEXT_DIM);
    firstHint.setLayoutParams(new LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    firstRow.addView(firstHint);
    firstRow.addView(btn(ctx, "+首进可见", v -> {
      settings.addFirstVisible(settings.getCurrentRoom());
      refresh.run();
      toast(ctx, "当前房间已加入首进可见名单");
    }));
    firstRow.addView(btn(ctx, "重置首进", v -> {
      settings.resetFirstEntry(settings.getCurrentRoom());
      toast(ctx, "已重置, 下次入场视为首次");
    }));
    panel.addView(firstRow);

    panel.addView(sectionLabel(ctx, "名单 (按主播显示, 未知则显示房间号)"));
    TextView listSummary = text(ctx, "", 11, false);
    listSummary.setTextColor(COLOR_TEXT_DIM);
    panel.addView(listSummary);

    // -- 伪装房间号 --
    panel.addView(sectionLabel(ctx, "进场上报伪装房间号"));
    LinearLayout fakeRow = row(ctx);
    EditText fakeInput = new EditText(ctx);
    fakeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
    fakeInput.setSingleLine(true);
    fakeInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
    fakeInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
    fakeInput.setTextColor(COLOR_TEXT);
    fakeInput.setHintTextColor(0xFF8A8A8A);
    fakeInput.setHint("如 510");
    GradientDrawable inputBg = new GradientDrawable();
    inputBg.setCornerRadius(dp(ctx, 8));
    inputBg.setColor(INPUT_COLOR);
    fakeInput.setBackground(inputBg);
    fakeInput.setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6));
    fakeRow.addView(fakeInput, new LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    fakeRow.addView(btn(ctx, "保存", v -> {
      String input = fakeInput.getText().toString().trim();
      if (input.matches("\\d{3,}")) {
        settings.setFakeRoomId(input);
        toast(ctx, "伪装房间号已保存");
      } else {
        toast(ctx, "房间号无效");
      }
    }));
    panel.addView(fakeRow);

    refresh = () -> {
      long room = settings.getCurrentRoom();
      String roomStr = room > 0 ? String.valueOf(room) : "-";
      Long anchor = room > 0 ? settings.getAnchor(room) : null;
      String anchorStr = anchor != null ? " (主播:" + anchor + ")" : "";
      roomLabel.setText("房间: " + roomStr);
      curRoom.setText("当前房间: " + roomStr + anchorStr);
      boolean black = Settings.MODE_BLACK.equals(settings.listMode);
      blackBtn.setTextColor(black ? COLOR_MODE_ON : COLOR_MODE_OFF);
      whiteBtn.setTextColor(black ? COLOR_MODE_OFF : COLOR_MODE_ON);
      listSummary.setText(settings.listSummary());
      if (!fakeInput.hasFocus() && !settings.fakeRoomId.equals(fakeInput.getText().toString())) {
        fakeInput.setText(settings.fakeRoomId);
      }
    };

    decor.addView(scroll);
    refresh.run();
  }

  /** 显示/隐藏设置面板, 显示时置于最前。 */
  private static void togglePanel(ViewGroup decor) {
    View panel = decor.findViewWithTag(TAG_PANEL);
    if (panel == null) return;
    if (panel.getVisibility() == View.VISIBLE) {
      panel.setVisibility(View.GONE);
    } else {
      panel.setVisibility(View.VISIBLE);
      panel.bringToFront();
    }
  }

  // ===== UI 工具方法 =====

  /** 水平行容器。 */
  private static LinearLayout row(Context ctx) {
    LinearLayout l = new LinearLayout(ctx);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.topMargin = dp(ctx, 8);
    lp.bottomMargin = dp(ctx, 4);
    l.setLayoutParams(lp);
    return l;
  }

  /** 分区小标题。 */
  private static TextView sectionLabel(Context ctx, String s) {
    TextView tv = text(ctx, s, 12, true);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.topMargin = dp(ctx, 12);
    lp.bottomMargin = dp(ctx, 2);
    tv.setLayoutParams(lp);
    return tv;
  }

  private static TextView text(Context ctx, String s, int sp, boolean bold) {
    TextView tv = new TextView(ctx);
    tv.setText(s);
    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    tv.setTextColor(COLOR_TEXT);
    if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
    return tv;
  }

  private static Switch sw(Context ctx, String label, boolean checked,
      CompoundButton.OnCheckedChangeListener listener) {
    Switch s = new Switch(ctx);
    s.setText(label);
    s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
    s.setTextColor(COLOR_TEXT);
    s.setChecked(checked);
    s.setOnCheckedChangeListener(listener);
    s.setPadding(0, dp(ctx, 8), 0, dp(ctx, 8));
    return s;
  }

  /** 深色圆角按钮 (显式背景, 不依赖宿主主题)。 */
  private static Button btn(Context ctx, String label, View.OnClickListener listener) {
    Button b = new Button(ctx);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
    b.setTextColor(COLOR_TEXT);
    b.setMinHeight(0);
    b.setMinimumHeight(0);
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5));
    GradientDrawable bg = new GradientDrawable();
    bg.setCornerRadius(dp(ctx, 8));
    bg.setColor(BTN_COLOR);
    b.setBackground(bg);
    b.setOnClickListener(listener);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.leftMargin = dp(ctx, 4);
    lp.rightMargin = dp(ctx, 4);
    b.setLayoutParams(lp);
    return b;
  }

  private static void toast(Context ctx, String s) {
    Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show();
  }

  private static int dp(Context ctx, int v) {
    return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
        ctx.getResources().getDisplayMetrics());
  }
}
