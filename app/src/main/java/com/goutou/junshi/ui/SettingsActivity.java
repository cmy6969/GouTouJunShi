package com.goutou.junshi.ui;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.goutou.junshi.answer.AiBank;
import com.goutou.junshi.answer.AxeBank;
import com.goutou.junshi.answer.Question;
import com.goutou.junshi.core.Cfg;

/**
 * Settings screen of the module.
 *
 * <p>Three groups only, matching the three features of the module: 答题 (answering), 反检测 (detection
 * guard) and 去广告 (ad removal).  The screen is built programmatically so the APK needs no resource
 * heavy UI toolkit and stays a few dozen kilobytes.</p>
 */
public final class SettingsActivity extends Activity {

    private final Map<String, Switch> switches = new LinkedHashMap<>();
    private final Map<String, RadioGroup> modeGroups = new LinkedHashMap<>();
    private EditText banksInput;
    private EditText bankFileInput;
    private EditText delayInput;
    private EditText axeTokenInput;
    private EditText axeBaseInput;
    private EditText axeCourseInput;
    private EditText axeLeftsView;
    private EditText aiBaseInput;
    private EditText aiModelInput;
    private EditText aiKeyInput;
    private EditText aiPromptInput;
    private EditText visionModelInput;
    private EditText visionBaseInput;
    private EditText visionKeyInput;
    private EditText aiResultView;
    private JSONObject config = ConfigIO.defaults();

    /** Auto-save plumbing: every change schedules a debounced save. */
    private final android.os.Handler saveHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable saveTask = new Runnable() {
        @Override
        public void run() {
            save();
        }
    };
    private volatile boolean uiReady;
    private String lastSavedJson;
    private TextView saveStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // System bars follow the palette (light background gets dark icons).
        try {
            int barColor = Style.bg(this);
            getWindow().setStatusBarColor(barColor);
            getWindow().setNavigationBarColor(barColor);
            if (Build.VERSION.SDK_INT >= 23) {
                View decor = getWindow().getDecorView();
                int flags = decor.getSystemUiVisibility();
                if (Style.dark(this)) {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                } else {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                }
                decor.setSystemUiVisibility(flags);
            }
            if (getActionBar() != null) {
                // The sheet draws its own title; the theme's green action bar would break the look.
                getActionBar().hide();
            }
        } catch (Throwable ignored) {
        }
        config = ConfigIO.read(this);
        setContentView(build());
        if (Build.VERSION.SDK_INT <= 28) {
            try {
                requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"}, 1);
            } catch (Throwable ignored) {
            }
        }
        syncFromHost();
    }

    /**
     * Pulls the configuration the host is actually running and fixes the selector if it differs.
     *
     * <p>The floating button's long press switches the answering mode at runtime; it is written into
     * the host's own config, but this app keeps a separate copy, so without this the screen would show
     * the stale mode.</p>
     */
    private void syncFromHost() {
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                final String json = ConfigIO.pull(SettingsActivity.this, 900L);
                if (json == null || !json.trim().startsWith("{")) {
                    return;
                }
                String mode;
                try {
                    JSONObject answer = new JSONObject(json).optJSONObject("answer");
                    mode = answer == null ? null : answer.optString("mode", null);
                } catch (Throwable t) {
                    return;
                }
                if (mode == null) {
                    return;
                }
                final String wanted = mode;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        RadioGroup rg = modeGroups.get("answer.mode");
                        if (rg == null) {
                            return;
                        }
                        int id = "manual".equalsIgnoreCase(wanted) ? 2001 : 2000;
                        if (rg.getCheckedRadioButtonId() != id) {
                            rg.check(id);
                            Toast.makeText(SettingsActivity.this,
                                    "已同步学习通当前状态：" + ("manual".equalsIgnoreCase(wanted)
                                            ? "点悬浮球答题" : "自动答题"),
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }, "lumen-config-sync");
        worker.setDaemon(true);
        worker.start();
    }

    private View build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(Style.bg(this));
        scroll.setClipToPadding(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, dp(18), pad, dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("狗头军师");
        title.setTextSize(28f);
        title.setTextColor(Style.text(this));
        title.setLetterSpacing(0.01f);
        title.setGravity(Gravity.START);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("学习通 · 答题 / 反检测 / 去广告");
        subtitle.setTextSize(13f);
        subtitle.setTextColor(Style.accent(this));
        subtitle.setPadding(0, dp(2), 0, dp(10));
        root.addView(subtitle);

        root.addView(hint("配置只存在本应用私有目录（含 Token / API Key），不会写入 Download 等公共目录。"
                + "保存后会立刻推送给正在运行的学习通；如果它没在运行，请先把学习通打开再回来保存。\n"
                + "作用域：请在框架中只勾选「学习通」。", 12f));

        // ------------------------------------------------------------ 反检测
        root.addView(section("反检测"));
        addSwitch(root, "stealth", "on", "启用反检测", "总开关。关闭后下面的子项都不生效。");
        addSwitch(root, "stealth", "detect_guard", "检测器对抗",
                "接管学习通内置的环境检测器（梆梆 / mshield / sea01 / study.util），"
                        + "并拦截检测线程触发的 System.exit 查杀。");
        addSwitch(root, "stealth", "unblock_capture", "解除防截屏",
                "去掉窗口上的 FLAG_SECURE，考试页可以正常截图 / 录屏。");
        addSwitch(root, "stealth", "proctor", "监考规避",
                "停用本机的人脸采集、屏幕监控上报，并让页面始终认为自己在前台、非分屏。");
        addSwitch(root, "stealth", "webview_stealth", "网页环境隐身",
                "网页里隐藏 webdriver、强制 visibilityState=visible，避免离开页面被记为异常。");
        addSwitch(root, "stealth", "hide_env", "深度环境伪装（高级）",
                "额外屏蔽 su / Magisk 路径、root 管理应用与框架调用栈。默认关闭：会全局影响文件与包名查询。");

        // ------------------------------------------------------------ 去广告
        root.addView(section("去广告"));
        addSwitch(root, "ads", "on", "启用去广告", "拦截开屏广告的预加载与展示。");
        addSwitch(root, "ads", "block_activity", "拦截广告落地页",
                "任何名字像广告的 Activity 都不会被拉起；不影响正常页面跳转。");

        // ------------------------------------------------------------ 答题
        root.addView(section("答题"));
        addSwitch(root, "answer", "on", "启用答题", "总开关。关闭后不注入页面脚本、不显示悬浮球。");
        addModeSelector(root, "answer", "mode", "答题方式",
                new String[]{"auto", "manual"},
                new String[]{"检测到题目就自动答", "点一下悬浮球，答当前题"},
                new String[]{"页面加载后自动提取题目、查库并填入答案。",
                        "页面加载后什么都不做，点悬浮球才查一次并填入；长按悬浮球也能临时切换。"},
                "auto");
        addSwitch(root, "answer", "bank_enabled", "题库答题（不含 AI）",
                "关闭后不查询 AXE / 本地题库 / 自定义接口；AI 兜底由它自己的开关控制，不受这里影响。");
        addModeSelector(root, "answer", "volume_key", "音量键答题",
                new String[]{"off", "down", "up", "both"},
                new String[]{"关闭", "按音量− 答一题", "按音量＋ 答一题", "音量＋/− 都能答"},
                new String[]{"不占用音量键。",
                        "只在作业/考试页生效，其他页面照常调音量。",
                        "只在作业/考试页生效，其他页面照常调音量。",
                        "只在作业/考试页生效，其他页面照常调音量。"},
                "down");
        addSwitch(root, "answer", "auto_fill", "自动填入答案",
                "识别到题目后直接把答案写进页面（选择题点选项，填空题写输入框）。");
        addSwitch(root, "answer", "auto_next", "自动下一题",
                "填完答案后自动点击「下一题」。整卷提交永远需要手动确认。");
        addSwitch(root, "answer", "ball", "显示悬浮窗",
                "页面右侧出现圆形「答」按钮：点一下答题、长按切换模式；关闭后页面完全干净，"
                        + "仍可用音量键或自动模式答题。");
        addSwitch(root, "answer", "toast", "显示 Toast 提示",
                "答题结果、没有题目等提示。关闭后只写日志，不再弹提示条。");

        delayInput = addEdit(root, "答题延迟 (毫秒)", String.valueOf(config.optJSONObject("answer") == null
                ? 900 : config.optJSONObject("answer").optInt("delay_ms", 900)), 1, InputType.TYPE_CLASS_NUMBER);

        // ------------------------------------------------------- AXE 题库 3.0
        root.addView(section("题库 · AXE 3.0"));
        JSONObject answerCfg = config.optJSONObject("answer");
        axeTokenInput = addEdit(root, "Token（32 位小写字母/数字）",
                answerCfg == null ? "" : answerCfg.optString("axe_token", ""), 1, InputType.TYPE_CLASS_TEXT);
        axeBaseInput = addEdit(root, "接口地址",
                answerCfg == null || answerCfg.optString("axe_base", "").isEmpty()
                        ? AxeBank.DEFAULT_BASE : answerCfg.optString("axe_base", ""), 1, InputType.TYPE_TEXT_VARIATION_URI);
        axeCourseInput = addEdit(root, "课程名（可选，AI 答题时提高命中率）",
                answerCfg == null ? "" : answerCfg.optString("axe_course", ""), 1, InputType.TYPE_CLASS_TEXT);
        addSwitch(root, "answer", "axe_ai", "AI 补充答题",
                "接口端的 ai=1：题库没收录时由 DeepSeek 兜底回答，可能额外消耗次数。");

        LinearLayout axeRow = new LinearLayout(this);
        axeRow.setOrientation(LinearLayout.HORIZONTAL);
        Button checkBtn = new Button(this);
        checkBtn.setText("查询剩余次数");
        checkBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                queryLefts();
            }
        });
        Button registerBtn = new Button(this);
        registerBtn.setText("注册 Token");
        registerBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                registerToken();
            }
        });
        axeRow.addView(checkBtn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        axeRow.addView(registerBtn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(axeRow);

        axeLeftsView = new EditText(this);
        axeLeftsView.setEnabled(false);
        axeLeftsView.setTextSize(13f);
        axeLeftsView.setHint("剩余次数");
        root.addView(axeLeftsView);

        root.addView(hint("使用方法：① 点「注册 Token」或在题库官网注册得到 token；"
                + "② 用该 token 在官网登录一次以激活（未激活是 0 次）；③ 查询剩余次数确认 > 0；"
                + "④ 保存配置。\n"
                + "答题时优先走 AXE，未命中再依次回落到 AI、自定义接口与本地题库。注册接口风控较严，"
                + "不要反复点击。", 11f));

        // ---------------------------------------------------------- AI 兜底
        root.addView(section("AI 兜底（题库未命中时）"));
        JSONObject aiCfg = answerCfg == null ? null : answerCfg.optJSONObject("ai");
        addSwitch(root, "answer.ai", "on", "启用 AI 兜底",
                "所有题库都没命中时，调用一次大模型作答。会产生你自己的 API 费用。");
        aiBaseInput = addEdit(root, "接口地址（OpenAI 兼容）",
                aiCfg == null || aiCfg.optString("base", "").isEmpty()
                        ? AiBank.DEFAULT_BASE : aiCfg.optString("base", ""), 1, InputType.TYPE_TEXT_VARIATION_URI);
        aiModelInput = addEdit(root, "模型",
                aiCfg == null || aiCfg.optString("model", "").isEmpty()
                        ? AiBank.DEFAULT_MODEL : aiCfg.optString("model", ""), 1, InputType.TYPE_CLASS_TEXT);
        aiKeyInput = addEdit(root, "API Key",
                aiCfg == null ? "" : aiCfg.optString("key", ""), 1, InputType.TYPE_CLASS_TEXT);
        aiPromptInput = addEdit(root, "自定义 system 提示词（留空用内置）",
                aiCfg == null ? "" : aiCfg.optString("system", ""), 2, InputType.TYPE_TEXT_FLAG_MULTI_LINE);

        // ---------------------------------------------------------- 图片题截图
        root.addView(section("图片题（截图问 AI）"));
        addSwitch(root, "answer.ai", "vision_on", "图片题用截图问 AI",
                "题干或选项是图片时（公式图、图片选项），自动截取整个题目（超出屏幕会滚动拼接）交给视觉模型；"
                        + "识别失败再回落到题库和文字 AI。");
        visionModelInput = addEdit(root, "视觉模型（留空则沿用上面的主模型）",
                aiCfg == null ? "" : aiCfg.optString("vision_model", ""), 1, InputType.TYPE_CLASS_TEXT);
        visionBaseInput = addEdit(root, "视觉接口（留空沿用上面的接口地址）",
                aiCfg == null ? "" : aiCfg.optString("vision_base", ""), 1, InputType.TYPE_TEXT_VARIATION_URI);
        visionKeyInput = addEdit(root, "视觉 Key（留空沿用上面的 Key）",
                aiCfg == null ? "" : aiCfg.optString("vision_key", ""), 1, InputType.TYPE_CLASS_TEXT);
        root.addView(hint("主模型本身支持图片（如 DeepSeek-V4-Flash）时留空即可；"
                + "也可填 glm-4v-flash（免费）/ qwen-vl-max / gpt-4o-mini 等视觉模型及其接口地址。", 12f));

        Button aiTest = new Button(this);
        aiTest.setText("测试 AI 连接");
        aiTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testAi();
            }
        });
        root.addView(aiTest);

        aiResultView = new EditText(this);
        aiResultView.setEnabled(false);
        aiResultView.setTextSize(13f);
        aiResultView.setHint("AI 测试结果");
        aiResultView.setMinLines(2);
        aiResultView.setMaxLines(6);
        root.addView(aiResultView);

        root.addView(hint("兼容 DeepSeek / OpenAI / 通义 / 智谱 / Kimi / one-api 中转，以及本地 "
                + "Ollama（http://127.0.0.1:11434/v1）或 LM Studio。\n"
                + "默认 deepseek-chat + https://api.deepseek.com；Key 只保存在本机配置里，不会上传到别处。",
                11f));

        banksInput = addEdit(root, "其它题库接口（每行一个 URL，可用占位符）",
                joinBanks(), 4, InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        root.addView(hint("占位符：{q} 题干（必填）、{type} 题型代码、{mtype} 题型名称、{opts} 选项。\n"
                + "例：https://your-bank.example/api/search?q={q}&type={mtype}\n"
                + "返回 JSON 里包含 answer / result / data.answer 任一字段即可。", 11f));

        bankFileInput = addEdit(root, "本地题库文件路径",
                config.optJSONObject("answer") == null ? "" : config.optJSONObject("answer").optString("bank_file", ""),
                1, InputType.TYPE_CLASS_TEXT);
        root.addView(hint("本地题库格式：每行「题干 TAB 答案」，留空表示不用本地题库。", 11f));

        // ------------------------------------------------------------ 操作
        Button save = new Button(this);
        save.setText("立即保存并推送");
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                lastSavedJson = null; // force a run even when nothing changed
                save();
            }
        });
        root.addView(save, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button clear = new Button(this);
        clear.setText("清空运行日志");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearLog();
            }
        });
        root.addView(clear);

        saveStatus = new TextView(this);
        saveStatus.setText("改动会自动保存");
        saveStatus.setTextSize(12f);
        saveStatus.setTextColor(Style.textDim(this));
        saveStatus.setPadding(dp(4), dp(12), 0, dp(4));
        root.addView(saveStatus);

        root.addView(hint("配置只保存在本应用私有目录，Token / API Key 不会写进 Download 等公共目录，也不会被打包进日志。\n"
                + "改动会自动保存并推送给正在运行的学习通；如果它没在运行，下次切回学习通前再改任意一项即可。\n"
                + "启用步骤：框架里启用模块 → 作用域勾选「学习通」→ 强制停止学习通 → 重新打开。", 12f));
        groupIntoCards(root);
        theme(scroll);
        uiReady = true;
        return scroll;
    }

    /**
     * Wraps everything that follows a section header into its own card, so the form reads as a stack
     * of grouped panels instead of one long undifferentiated list.
     */
    private void groupIntoCards(LinearLayout root) {
        java.util.List<View> children = new ArrayList<>();
        for (int i = 0; i < root.getChildCount(); i++) {
            children.add(root.getChildAt(i));
        }
        root.removeAllViews();
        LinearLayout card = null;
        for (View v : children) {
            boolean isSection = v instanceof TextView && Integer.valueOf(1).equals(v.getTag());
            if (isSection) {
                root.addView(v);
                card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setBackground(Style.card(this));
                card.setPadding(dp(16), dp(6), dp(16), dp(10));
                card.setElevation(dp(1));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = dp(4);
                root.addView(card, lp);
                continue;
            }
            if (card != null) {
                card.addView(v);
            } else {
                root.addView(v);
            }
        }
    }

    // ------------------------------------------------------------------ theme

    /** Walks the finished tree and applies the palette to every control. */
    private void theme(View v) {
        if (v instanceof Button) {
            Button b = (Button) v;
            boolean primary = "保存配置".equals(String.valueOf(b.getText()));
            b.setBackground(primary ? Style.primary(this) : Style.secondary(this));
            b.setTextColor(primary ? Style.onAccent() : Style.accent(this));
            b.setTextSize(15f);
            b.setAllCaps(false);
            b.setMinHeight(dp(48));
            b.setStateListAnimator(null);
        } else if (v instanceof EditText) {
            EditText e = (EditText) v;
            e.setTextColor(Style.text(this));
            e.setHintTextColor(Style.textDim(this));
            e.setBackground(Style.fieldBox(this));
            e.setPadding(dp(14), dp(12), dp(14), dp(12));
            e.setTextSize(14f);
        } else if (v instanceof Switch) {
            tintSwitch((Switch) v);
        } else if (v instanceof RadioButton) {
            RadioButton r = (RadioButton) v;
            r.setTextColor(Style.text(this));
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                r.setButtonTintList(android.content.res.ColorStateList.valueOf(Style.accent(this)));
            }
        } else if (v instanceof TextView) {
            TextView t = (TextView) v;
            Integer tag = (Integer) t.getTag();
            if (tag == null || tag != 1) {
                if (t.getCurrentTextColor() == 0xFF000000) {
                    t.setTextColor(Style.text(this));
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                theme(g.getChildAt(i));
            }
        }
    }

    private void tintSwitch(Switch sw) {
        // The platform switch paints its own rounded container; inside a card that reads as a second
        // card, so it is dropped (background + foreground ripple) and only track/thumb keep colour.
        sw.setBackground(null);
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            sw.setForeground(null);
        }
        sw.setPadding(0, dp(10), 0, dp(10));
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            int accent = Style.accent(this);
            android.content.res.ColorStateList track = new android.content.res.ColorStateList(
                    new int[][]{
                            new int[]{android.R.attr.state_checked},
                            new int[]{}
                    },
                    new int[]{accent, Style.outline(this)});
            android.content.res.ColorStateList thumb = new android.content.res.ColorStateList(
                    new int[][]{
                            new int[]{android.R.attr.state_checked},
                            new int[]{}
                    },
                    new int[]{0xFFFFFFFF, Style.dark(this) ? 0xFFB9C0CA : 0xFFFFFFFF});
            sw.setTrackTintList(track);
            sw.setThumbTintList(thumb);
        }
        sw.setTextColor(Style.text(this));
    }

    // ------------------------------------------------------------------ build

    private View section(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15f);
        tv.setTextColor(Style.accent(this));
        tv.setLetterSpacing(0.06f);
        tv.setTag(1); // marks a section header (kept out of the generic text colouring)
        tv.setPadding(0, dp(22), 0, dp(6));
        return tv;
    }

    private View hint(String text, float size) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(size);
        tv.setTextColor(Style.textDim(this));
        tv.setPadding(0, dp(4), 0, dp(4));
        return tv;
    }

    private void addSwitch(LinearLayout root, String group, String key, String title, String summary) {
        // Modern list row: title + description on the left, bare switch on the right. The switch
        // carries no text, so the platform theme cannot draw its rounded container around the row.
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(15f);
        texts.addView(name);

        TextView sub = new TextView(this);
        sub.setText(summary);
        sub.setTextSize(12f);
        sub.setTextColor(Style.textDim(this));
        sub.setPadding(0, dp(2), dp(8), 0);
        texts.addView(sub);

        row.addView(texts, tlp);

        Switch sw = new Switch(this);
        sw.setShowText(false);
        sw.setChecked(currentBoolean(group, key, defaultFor(group, key)));
        sw.setElevation(0f);
        sw.setStateListAnimator(null);
        row.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                save();
            }
        });
        switches.put(group + "." + key, sw);

        root.addView(row);
    }

    /** Two exclusive options stored as a string ({@code answer.mode}). */
    private void addModeSelector(LinearLayout root, String group, String key, String title,
                                 String[] values, String[] labels, String[] hints, String def) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, dp(4));

        TextView head = new TextView(this);
        head.setText(title);
        head.setTextSize(15f);
        row.addView(head);

        RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(RadioGroup.VERTICAL);
        String current = currentString(group, key, def);
        for (int i = 0; i < values.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(2000 + i);
            rb.setText(labels[i]);
            rb.setTextSize(14f);
            rb.setTag(values[i]);
            rg.addView(rb);
            if (values[i].equalsIgnoreCase(current)) {
                rg.check(2000 + i);
            }
            // The hint belongs to the option above it, so it is added right after that option.
            if (i < hints.length && hints[i] != null) {
                TextView hint = new TextView(this);
                hint.setText(hints[i]);
                hint.setTextSize(12f);
                hint.setAlpha(0.6f);
                hint.setPadding(dp(24), 0, 0, dp(2));
                rg.addView(hint);
            }
        }
        rg.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group1, int checkedId) {
                save();
            }
        });
        row.addView(rg);
        modeGroups.put(group + "." + key, rg);
        root.addView(row);
    }

    /** Reads a string that may live in a nested group such as {@code answer}. */
    private String currentString(String group, String key, String def) {
        JSONObject node = config;
        for (String part : group.split("\\.")) {
            if (node == null) {
                return def;
            }
            node = node.optJSONObject(part);
        }
        return node == null ? def : node.optString(key, def);
    }

    /** Reads a boolean that may live in a nested group such as {@code answer.ai}. */
    private boolean currentBoolean(String group, String key, boolean def) {
        JSONObject node = config;
        for (String part : group.split("\\.")) {
            if (node == null) {
                return def;
            }
            node = node.optJSONObject(part);
        }
        return node == null ? def : node.optBoolean(key, def);
    }

    private static boolean defaultFor(String group, String key) {
        if ("answer.ai".equals(group)) {
            return false;
        }
        switch (key) {
            case "hide_env":
            case "auto_next":
            case "axe_ai":
                return false;
            default:
                return true;
        }
    }

    private EditText addEdit(LinearLayout root, String title, String value, int lines, int type) {
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextSize(14f);
        label.setTextColor(Style.textDim(this));
        label.setPadding(0, dp(12), 0, dp(2));
        root.addView(label);

        EditText edit = new EditText(this);
        edit.setText(value);
        edit.setInputType(type | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setMinLines(lines);
        edit.setMaxLines(Math.max(lines, 1));
        edit.setTextSize(13f);
        edit.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s2, int a, int b, int c) { }

            public void onTextChanged(CharSequence s2, int a, int b, int c) { }

            public void afterTextChanged(android.text.Editable s2) {
                scheduleSave();
            }
        });
        root.addView(edit);
        return edit;
    }

    private String joinBanks() {
        JSONObject answer = config.optJSONObject("answer");
        if (answer == null) {
            return "";
        }
        JSONArray arr = answer.optJSONArray("banks");
        if (arr == null) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "");
            if (!s.trim().isEmpty()) {
                lines.add(s.trim());
            }
        }
        return android.text.TextUtils.join("\n", lines);
    }

    // ------------------------------------------------------------------- save

    /** Collects the whole screen into the config JSON (also used by the AI test button). */
    private JSONObject buildConfig() {
        JSONObject root = new JSONObject();
        JSONObject stealth = new JSONObject();
        JSONObject ads = new JSONObject();
        JSONObject answer = new JSONObject();
        JSONObject ai = new JSONObject();
        try {
            root.put("v", 1);
            for (Map.Entry<String, Switch> e : switches.entrySet()) {
                String path = e.getKey();
                int at = path.lastIndexOf('.');
                String group = path.substring(0, at);
                String key = path.substring(at + 1);
                boolean value = e.getValue().isChecked();
                switch (group) {
                    case "stealth":
                        stealth.put(key, value);
                        break;
                    case "ads":
                        ads.put(key, value);
                        break;
                    case "answer":
                        answer.put(key, value);
                        break;
                    case "answer.ai":
                        ai.put(key, value);
                        break;
                    default:
                        break;
                }
            }
            answer.put("delay_ms", parse(delayInput.getText().toString(), 900));
            for (Map.Entry<String, RadioGroup> e : modeGroups.entrySet()) {
                String path = e.getKey();
                int at = path.lastIndexOf('.');
                String group = path.substring(0, at);
                String key = path.substring(at + 1);
                RadioGroup rg = e.getValue();
                int checked = rg.getCheckedRadioButtonId();
                View picked = checked <= 0 ? null : rg.findViewById(checked);
                String value = picked == null ? "auto" : String.valueOf(picked.getTag());
                if ("answer".equals(group)) {
                    answer.put(key, value);
                }
            }
            answer.put("banks", splitBanks(banksInput.getText().toString()));
            answer.put("bank_file", bankFileInput.getText().toString().trim());
            answer.put("axe_token", axeTokenInput.getText().toString().trim());
            String base = axeBaseInput.getText().toString().trim();
            answer.put("axe_base", base.isEmpty() ? AxeBank.DEFAULT_BASE : base);
            answer.put("axe_course", axeCourseInput.getText().toString().trim());

            // Values that are only editable in the JSON file keep their current setting.
            JSONObject oldAi = config.optJSONObject("answer") == null
                    ? null : config.optJSONObject("answer").optJSONObject("ai");
            ai.put("base", aiBaseInput.getText().toString().trim().isEmpty()
                    ? AiBank.DEFAULT_BASE : aiBaseInput.getText().toString().trim());
            ai.put("model", aiModelInput.getText().toString().trim().isEmpty()
                    ? AiBank.DEFAULT_MODEL : aiModelInput.getText().toString().trim());
            ai.put("key", aiKeyInput.getText().toString().trim());
            ai.put("vision_base", visionBaseInput.getText().toString().trim());
            ai.put("vision_model", visionModelInput.getText().toString().trim());
            ai.put("vision_key", visionKeyInput.getText().toString().trim());
            // Only editable in the JSON file; keep whatever is already there.
            if (oldAi != null && !oldAi.optString("vision_path", "").isEmpty()) {
                ai.put("vision_path", oldAi.optString("vision_path", ""));
            }
            if (oldAi != null && !oldAi.optString("vision_prompt", "").isEmpty()) {
                ai.put("vision_prompt", oldAi.optString("vision_prompt", ""));
            }
            ai.put("system", aiPromptInput.getText().toString().trim());
            ai.put("path", oldAi == null ? AiBank.DEFAULT_PATH : oldAi.optString("path", AiBank.DEFAULT_PATH));
            ai.put("timeout_ms", oldAi == null ? 20000 : oldAi.optInt("timeout_ms", 20000));

            answer.put("ai", ai);
            root.put("stealth", stealth);
            root.put("ads", ads);
            root.put("answer", answer);
        } catch (Throwable ignored) {
        }
        return root;
    }

    /**
     * Persists the current form and pushes it to the host.
     *
     * <p>Runs on every change (auto-save); the button only forces an immediate run.  Identical
     * content is skipped so toggling back and forth does not spam the host with broadcasts.</p>
     */
    private void save() {
        if (!uiReady) {
            return;
        }
        try {
            JSONObject root = buildConfig();
            final String json = root.toString();
            if (json.equals(lastSavedJson)) {
                setStatus("配置无变化");
                return;
            }
            lastSavedJson = json;
            setStatus("正在保存…");
            ConfigIO.saveAsync(this, root, new ConfigIO.SaveCallback() {
                @Override
                public void onSaved(ConfigIO.Result result) {
                    String msg;
                    if (!result.written) {
                        msg = "保存失败：无法写入配置";
                    } else if (result.pushed) {
                        msg = "已自动保存 · 学习通已应用 " + time();
                    } else {
                        msg = "已自动保存 · 学习通未响应（打开学习通后会自动生效） " + time();
                    }
                    setStatus(msg);
                    if (!result.written) {
                        Toast.makeText(SettingsActivity.this, msg, Toast.LENGTH_LONG).show();
                    }
                }
            });
        } catch (Throwable t) {
            setStatus("保存失败: " + t);
            Toast.makeText(this, "保存失败: " + t, Toast.LENGTH_LONG).show();
        }
    }

    private static String time() {
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date());
    }

    /** Coalesces rapid edits (typing) into one save. */
    private void scheduleSave() {
        if (!uiReady) {
            return;
        }
        saveHandler.removeCallbacks(saveTask);
        saveHandler.postDelayed(saveTask, 700L);
    }

    private void setStatus(String text) {
        if (saveStatus != null) {
            saveStatus.setText(text);
        }
    }

    private static int parse(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    // ------------------------------------------------------------- AXE 题库

    private String axeToken() {
        return axeTokenInput == null ? "" : axeTokenInput.getText().toString().trim();
    }

    private String axeBase() {
        String b = axeBaseInput == null ? "" : axeBaseInput.getText().toString().trim();
        return b.isEmpty() ? AxeBank.DEFAULT_BASE : b;
    }

    private void queryLefts() {
        final String token = axeToken();
        final String base = axeBase();
        if (token.isEmpty()) {
            Toast.makeText(this, "请先填写 token", Toast.LENGTH_SHORT).show();
            return;
        }
        setLeftsText("查询中…");
        runAsync(new Runnable() {
            @Override
            public void run() {
                final AxeBank.Status status = AxeBank.check(base, token);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setLeftsText(status.message
                                + (status.lefts >= 0 ? ("（" + status.lefts + " 次）") : ""));
                    }
                });
            }
        });
    }

    private void registerToken() {
        final String token = axeToken();
        final String base = axeBase();
        if (!token.matches("^[0-9a-z]{32}$")) {
            Toast.makeText(this, "token 必须是 32 位小写字母/数字", Toast.LENGTH_SHORT).show();
            return;
        }
        setLeftsText("注册中…");
        runAsync(new Runnable() {
            @Override
            public void run() {
                final String msg = AxeBank.register(base, token);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setLeftsText(msg);
                        Toast.makeText(SettingsActivity.this, msg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void runAsync(Runnable body) {
        Thread t = new Thread(body, "lumen-settings-io");
        t.setDaemon(true);
        t.start();
    }

    private void setLeftsText(String text) {
        if (axeLeftsView != null) {
            axeLeftsView.setText(text);
        }
    }

    // ---------------------------------------------------------------- AI 兜底

    private void testAi() {
        final Cfg.Snapshot snapshot = new Cfg.Snapshot(buildConfig());
        final Question question = new Question();
        question.id = "ai-test";
        question.type = 0;
        question.stem = "下列哪个是 Java 的访问修饰符？";
        question.options.add(new Question.Option("A", "final"));
        question.options.add(new Question.Option("B", "static"));
        question.options.add(new Question.Option("C", "private"));
        question.options.add(new Question.Option("D", "extends"));

        setAiText("请求中…");
        runAsync(new Runnable() {
            @Override
            public void run() {
                String outcome;
                try {
                    AiBank.Hit hit = AiBank.query(question, snapshot);
                    if (hit == null) {
                        outcome = "AI 兜底未启用或未填写 Key";
                    } else if (!hit.answer.isEmpty()) {
                        outcome = "回答成功，答案：" + hit.answer + "（期望 C）";
                    } else {
                        outcome = "请求失败：" + hit.note;
                    }
                } catch (Throwable t) {
                    outcome = "请求异常：" + t;
                }
                final String shown = outcome;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setAiText(shown);
                    }
                });
            }
        });
    }

    private void setAiText(String text) {
        if (aiResultView != null) {
            aiResultView.setText(text);
        }
    }

    private static JSONArray splitBanks(String text) {
        JSONArray arr = new JSONArray();
        for (String line : text.split("\n")) {
            String v = line.trim();
            if (!v.isEmpty() && !v.startsWith("#")) {
                arr.put(v);
            }
        }
        return arr;
    }

    private void clearLog() {        try {
            java.io.File dir = new java.io.File(android.os.Environment
                    .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), DIR);
            java.io.File log = new java.io.File(dir, "lumen.log");
            boolean ok = !log.exists() || log.delete();
            Toast.makeText(this, ok ? "日志已清空" : "无法删除日志", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "无法删除日志", Toast.LENGTH_SHORT).show();
        }
    }

    /** Opens the system "all files access" page for the host app. */
    private void openAllFilesSettings() {
        String[] actions = {
                "android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION",
                "android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION"
        };
        for (String action : actions) {
            try {
                android.content.Intent intent = new android.content.Intent(action);
                intent.setData(android.net.Uri.parse("package:com.chaoxing.mobile"));
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                Toast.makeText(this, "请为『学习通』打开该开关", Toast.LENGTH_LONG).show();
                return;
            } catch (Throwable ignored) {
            }
        }
        Toast.makeText(this, "当前系统没有这个设置页，请在系统设置中手动授予", Toast.LENGTH_LONG).show();
    }

    private static final String DIR = ConfigIO.DIR;

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** Kept for the settings deep link used by frameworks that open the module page. */
    public static final String EXTRA_RELOAD = Cfg.ACTION_PUSH;
}
