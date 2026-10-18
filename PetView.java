package pet.margyt;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.Random;

/**
 * The pet. One View, drawn entirely on a Canvas, laid over a screen's decor view.
 *
 * Touch: tap = pet it, long-press = open the menu, drag = move it around.
 * Menu: feed, water, play, sleep/wake, take avatar from the current screen.
 */
final class PetView extends View {

    interface Logger {
        void log(String line);
    }

    // faces
    private static final int F_NORMAL = 0;
    private static final int F_HAPPY = 1;
    private static final int F_SAD = 2;
    private static final int F_SLEEP = 3;
    private static final int F_SURPRISE = 4;
    private static final int F_ANGRY = 5;

    private static final int BTN_FEED = 0;
    private static final int BTN_WATER = 1;
    private static final int BTN_PLAY = 2;
    private static final int BTN_SLEEP = 3;
    private static final int BTN_AVATAR = 4;
    private static final int BTN_COUNT = 5;

    private static final class Particle {
        String s;
        float x, y, vx, vy, life, max, size;
    }

    private final SharedPreferences prefs;
    private final File avatarFile;
    private final Logger logger;
    private final boolean ru;
    private final float d;
    private final int wPx, hPx;
    private final float r;
    private final float slop;
    private final Random rnd = new Random();

    // paints
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bubblePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final Matrix matrix = new Matrix();

    // host
    private ViewGroup host;
    private boolean placed;
    private int lastRw, lastRh;

    // position (centre of the pet's head, in host coordinates)
    private float px, py;
    private float fx, fy;

    // stats 0..100
    private float hunger = 70f, thirst = 70f, mood = 70f, energy = 80f;
    private boolean sleeping;

    // avatar
    private Bitmap avatar;
    private BitmapShader avatarShader;
    private int bodyColor = 0xFFFFC27A;
    private int bodyDark = 0xFFE29A4F;
    private int headColor = 0xFFFFD9A8;

    // animation state
    private boolean running;
    private long lastFrame;
    private float t;
    private float jumpH, jumpV;
    private float lift;
    private float squashT, shakeT, wiggleT, eatT, drinkT, playT;
    private float blinkT = 3f;
    private float zzzT, complainT = 40f, saveT;
    private float emoUntil;
    private int emoFace;
    private String emoBadge = "";
    private final ArrayList<Particle> parts = new ArrayList<Particle>();

    // bubble
    private String bubbleText;
    private float bubbleUntil;
    private StaticLayout bubbleLayout;
    private float lastTalk = -100f;

    // interaction
    private boolean menuOpen;
    private boolean flip;
    private float menuUntil;
    private boolean dragging;
    private int mode; // 0 none, 1 pet, 2 button
    private int downBtn = -1;
    private int pressedBtn = -1;
    private float downX, downY, startPx, startPy;
    private boolean moved, longFired;
    private final ArrayList<Long> pets = new ArrayList<Long>();
    private final ArrayList<Long> refusals = new ArrayList<Long>();

    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            if (mode == 1 && !moved) {
                longFired = true;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                toggleMenu();
            }
        }
    };

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(0.05f, (now - lastFrame) / 1000f);
            lastFrame = now;
            try {
                step(dt);
            } catch (Throwable e) {
                logger.log("pet: step failed: " + e);
            }
            invalidate();
            postOnAnimation(this);
        }
    };

    PetView(Context context, SharedPreferences prefs, File avatarFile, Logger logger) {
        super(context);
        this.prefs = prefs;
        this.avatarFile = avatarFile;
        this.logger = logger;
        this.ru = "ru".equals(Locale.getDefault().getLanguage());
        this.d = context.getResources().getDisplayMetrics().density;
        this.wPx = (int) (300f * d);
        this.hPx = (int) (340f * d);
        this.r = 38f * d;
        this.slop = Math.max(ViewConfiguration.get(context).getScaledTouchSlop(), 8f * d);

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        bubblePaint.setColor(0xFF222222);
        bubblePaint.setTextSize(13f * d);

        setWillNotDraw(false);
        load();
    }

    // ------------------------------------------------------------------ attach

    void attachTo(ViewGroup root) {
        ViewParent old = getParent();
        if (old == root) {
            bringToFront();
            return;
        }
        if (old instanceof ViewGroup) {
            ((ViewGroup) old).removeView(this);
        }
        host = root;
        placed = false;
        root.addView(this, new FrameLayout.LayoutParams(wPx, hPx, Gravity.TOP | Gravity.LEFT));
        bringToFront();
    }

    void detachFrom(View root) {
        if (getParent() == root && root instanceof ViewGroup) {
            save();
            ((ViewGroup) root).removeView(this);
            if (host == root) {
                host = null;
            }
        }
    }

    void destroy() {
        running = false;
        removeCallbacks(frame);
        removeCallbacks(longPress);
        save();
        ViewParent p = getParent();
        if (p instanceof ViewGroup) {
            ((ViewGroup) p).removeView(this);
        }
        host = null;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        lastFrame = SystemClock.uptimeMillis();
        postOnAnimation(frame);
        if (!prefs.getBoolean("hint_shown", false)) {
            prefs.edit().putBoolean("hint_shown", true).apply();
            say(tr("Тап — погладить, удержание — меню, тащи — переместить!",
                    "Tap to pet, hold for the menu, drag to move me!"), 8f);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        removeCallbacks(frame);
        removeCallbacks(longPress);
        mode = 0;
        dragging = false;
        save();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(wPx, hPx);
    }

    // ------------------------------------------------------------------ helpers

    private String tr(String ruText, String enText) {
        return ru ? ruText : enText;
    }

    /** pairs: ru1, en1, ru2, en2, ... */
    private String pick(String... pairs) {
        int n = pairs.length / 2;
        int i = rnd.nextInt(n);
        return pairs[i * 2 + (ru ? 0 : 1)];
    }

    private static float clamp100(float v) {
        return v < 0f ? 0f : (v > 100f ? 100f : v);
    }

    private float minStat() {
        return Math.min(Math.min(hunger, thirst), Math.min(mood, energy));
    }

    private static int darker(int c, float k) {
        int rr = (int) (((c >> 16) & 0xFF) * k);
        int gg = (int) (((c >> 8) & 0xFF) * k);
        int bb = (int) ((c & 0xFF) * k);
        return 0xFF000000 | (rr << 16) | (gg << 8) | bb;
    }

    private static int lighter(int c, float k) {
        int rr = (c >> 16) & 0xFF;
        int gg = (c >> 8) & 0xFF;
        int bb = c & 0xFF;
        rr += (int) ((255 - rr) * k);
        gg += (int) ((255 - gg) * k);
        bb += (int) ((255 - bb) * k);
        return 0xFF000000 | (rr << 16) | (gg << 8) | bb;
    }

    private void say(String s, float secs) {
        bubbleText = s;
        bubbleLayout = null;
        bubbleUntil = t + secs;
        lastTalk = t;
    }

    private void react(String badge, int face, float secs) {
        emoBadge = badge;
        emoFace = face;
        emoUntil = t + secs;
    }

    private void jump(float heightDp) {
        float g = 2200f * d;
        jumpV = (float) Math.sqrt(2f * g * heightDp * d);
    }

    private void burst(String s, int n, float x, float y, float spreadDp, float upDp, float sizeDp) {
        for (int i = 0; i < n && parts.size() < 40; i++) {
            Particle p = new Particle();
            p.s = s;
            p.x = x + (rnd.nextFloat() - 0.5f) * spreadDp * d;
            p.y = y + (rnd.nextFloat() - 0.5f) * 10f * d;
            p.vx = (rnd.nextFloat() - 0.5f) * 50f * d;
            p.vy = -(upDp * 0.6f + rnd.nextFloat() * upDp * 0.8f) * d;
            p.max = 0.9f + rnd.nextFloat() * 0.7f;
            p.life = p.max;
            p.size = sizeDp * d * (0.8f + rnd.nextFloat() * 0.5f);
            parts.add(p);
        }
    }

    private void touch() {
        menuUntil = t + 10f;
    }

    // ------------------------------------------------------------------ persistence

    private void load() {
        hunger = prefs.getFloat("hunger", 70f);
        thirst = prefs.getFloat("thirst", 70f);
        mood = prefs.getFloat("mood", 70f);
        energy = prefs.getFloat("energy", 80f);
        sleeping = prefs.getBoolean("sleeping", false);
        fx = prefs.getFloat("fx", 0.82f);
        fy = prefs.getFloat("fy", 0.55f);
        long ts = prefs.getLong("ts", 0L);
        if (ts > 0L) {
            float hours = (System.currentTimeMillis() - ts) / 3600000f;
            if (hours > 0f) {
                tickStats(Math.min(hours, 24f));
            }
        }
        try {
            if (avatarFile.exists()) {
                Bitmap b = BitmapFactory.decodeFile(avatarFile.getAbsolutePath());
                if (b != null) {
                    setAvatar(b, false);
                }
            }
        } catch (Throwable e) {
            logger.log("pet: avatar load failed: " + e);
        }
    }

    private void save() {
        try {
            SharedPreferences.Editor ed = prefs.edit();
            ed.putFloat("hunger", hunger);
            ed.putFloat("thirst", thirst);
            ed.putFloat("mood", mood);
            ed.putFloat("energy", energy);
            ed.putBoolean("sleeping", sleeping);
            ed.putLong("ts", System.currentTimeMillis());
            if (host != null && host.getWidth() > 0 && host.getHeight() > 0 && placed) {
                fx = px / host.getWidth();
                fy = py / host.getHeight();
            }
            ed.putFloat("fx", fx);
            ed.putFloat("fy", fy);
            ed.apply();
        } catch (Throwable e) {
            logger.log("pet: save failed: " + e);
        }
    }

    // ------------------------------------------------------------------ stats

    private void tickStats(float hours) {
        if (sleeping) {
            energy += 35f * hours;
            hunger -= 4f * hours;
            thirst -= 5f * hours;
            mood -= 1f * hours;
        } else {
            hunger -= 10f * hours;
            thirst -= 14f * hours;
            energy -= 3f * hours;
            float md = (hunger < 20f || thirst < 20f) ? 12f : 5f;
            mood -= md * hours;
        }
        hunger = clamp100(hunger);
        thirst = clamp100(thirst);
        mood = clamp100(mood);
        energy = clamp100(energy);
        if (sleeping && energy >= 100f) {
            sleeping = false;
            wokeByItself = true;
        }
    }

    private boolean wokeByItself;

    // ------------------------------------------------------------------ position

    private boolean place() {
        if (host == null) {
            return false;
        }
        int rw = host.getWidth();
        int rh = host.getHeight();
        if (rw <= 0 || rh <= 0) {
            return false;
        }
        lastRw = rw;
        lastRh = rh;
        px = fx * rw;
        py = fy * rh;
        clampPos();
        applyTranslation();
        return true;
    }

    private void clampPos() {
        if (host == null) {
            return;
        }
        int rw = host.getWidth();
        int rh = host.getHeight();
        if (rw <= 0 || rh <= 0) {
            return;
        }
        float minX = 1.15f * r;
        float maxX = rw - 1.15f * r;
        float minY = 1.5f * r;
        float maxY = rh - 1.9f * r;
        px = Math.max(minX, Math.min(maxX, px));
        py = Math.max(minY, Math.min(Math.max(minY, maxY), py));
    }

    private void applyTranslation() {
        setTranslationX(px - wPx / 2f);
        setTranslationY(py - hPx / 2f);
    }

    private void updateFlip() {
        if (host != null && host.getHeight() > 0) {
            flip = py > host.getHeight() * 0.55f;
        }
    }

    // ------------------------------------------------------------------ simulation

    private void step(float dt) {
        t += dt;

        if (!placed) {
            placed = place();
        } else if (host != null && (host.getWidth() != lastRw || host.getHeight() != lastRh)) {
            placed = place();
        }

        tickStats(dt / 3600f);
        if (wokeByItself) {
            wokeByItself = false;
            say(tr("Выспался! Доброе утро!", "All rested! Good morning!"), 3f);
            react("😊", F_HAPPY, 3f);
            jump(14f);
        }

        // timers
        if (shakeT > 0f) shakeT -= dt;
        if (wiggleT > 0f) wiggleT -= dt;
        if (eatT > 0f) eatT -= dt;
        if (drinkT > 0f) drinkT -= dt;
        if (playT > 0f) playT -= dt;
        if (squashT > 0f) squashT -= dt;

        // lift while dragged
        float liftTarget = dragging ? 0.3f * r : 0f;
        lift += (liftTarget - lift) * Math.min(1f, dt * 14f);

        // jump physics
        if (jumpH > 0f || jumpV != 0f) {
            jumpV -= 2200f * d * dt;
            jumpH += jumpV * dt;
            if (jumpH <= 0f) {
                jumpH = 0f;
                if (Math.abs(jumpV) > 420f * d) {
                    squashT = 0.28f;
                    jumpV = -jumpV * 0.35f;
                } else {
                    jumpV = 0f;
                }
            }
        }

        // blink
        blinkT -= dt;
        if (blinkT < -0.13f) {
            blinkT = 2.5f + rnd.nextFloat() * 3f;
        }

        // sleeping
        if (sleeping) {
            zzzT -= dt;
            if (zzzT <= 0f) {
                zzzT = 1.3f;
                burst("💤", 1, wPx / 2f + 0.7f * r, hPx / 2f - 0.6f * r, 6f, 30f, 16f);
            }
        }

        // particles
        for (int i = parts.size() - 1; i >= 0; i--) {
            Particle p = parts.get(i);
            p.life -= dt;
            if (p.life <= 0f) {
                parts.remove(i);
                continue;
            }
            p.x += p.vx * dt;
            p.y += p.vy * dt;
        }

        // menu timeout
        if (menuOpen && t > menuUntil && mode == 0) {
            menuOpen = false;
        }

        // small complaints
        complainT -= dt;
        if (complainT <= 0f) {
            complainT = 45f + rnd.nextFloat() * 30f;
            if (!sleeping && !dragging && t > bubbleUntil && t - lastTalk > 30f) {
                complain();
            }
        }

        // autosave
        saveT += dt;
        if (saveT > 20f) {
            saveT = 0f;
            save();
        }
    }

    private void complain() {
        if (hunger < 25f) {
            say(pick("Я голодный… 🍖", "I'm hungry… 🍖",
                    "В животе урчит…", "My tummy is rumbling…"), 4f);
            react("🥺", F_SAD, 4f);
        } else if (thirst < 25f) {
            say(pick("Хочу пить… 💧", "I'm thirsty… 💧",
                    "Дай водички, пожалуйста", "Water, please?"), 4f);
            react("🥺", F_SAD, 4f);
        } else if (energy < 20f) {
            say(pick("Глаза слипаются… 😴", "So sleepy… 😴"), 4f);
            react("🥱", F_SAD, 4f);
        } else if (mood < 25f) {
            say(pick("Мне скучно, поиграй со мной!", "I'm bored, play with me!",
                    "Погладь меня, пожалуйста…", "Pet me, please…"), 4f);
            react("😒", F_SAD, 4f);
        } else if (mood > 75f) {
            say(pick("Ты лучший! 💗", "You're the best! 💗",
                    "Как дела?", "How's it going?"), 3f);
            wiggleT = 0.6f;
        }
    }

    // ------------------------------------------------------------------ actions

    private void wake(boolean sayHello) {
        if (!sleeping) {
            return;
        }
        sleeping = false;
        if (sayHello) {
            say(tr("Доброе утро! ☀️", "Good morning! ☀️"), 2.5f);
            react("😊", F_HAPPY, 2.5f);
            jump(10f);
        }
    }

    private void doPet() {
        touch();
        if (sleeping) {
            wake(false);
            mood = clamp100(mood - 6f);
            shakeT = 0.6f;
            say(tr("Ааа! Я же спал!..", "Hey! I was sleeping!.."), 3f);
            react("😠", F_ANGRY, 3f);
            return;
        }
        long now = SystemClock.uptimeMillis();
        pets.add(now);
        for (int i = pets.size() - 1; i >= 0; i--) {
            if (now - pets.get(i) > 5000L) {
                pets.remove(i);
            }
        }
        float cx = wPx / 2f, cy = hPx / 2f;
        if (pets.size() >= 9) {
            pets.clear();
            mood = clamp100(mood - 4f);
            shakeT = 0.7f;
            say(tr("Ой, хватит, голова кружится! 😵", "Okay okay, I'm dizzy now! 😵"), 3f);
            react("😵", F_SURPRISE, 3f);
            return;
        }
        mood = clamp100(mood + 3.5f);
        wiggleT = 0.6f;
        jump(6f);
        burst("💗", 3, cx, cy - r, 50f, 70f, 18f);
        react("😍", F_HAPPY, 2f);
        if (t > bubbleUntil && rnd.nextInt(10) < 5) {
            say(pick("Мурр~ 💕", "Purr~ 💕",
                    "Хи-хи, приятно!", "Hehe, that feels nice!",
                    "Люблю, когда гладят", "I love being petted",
                    "Ещё-ещё!", "More, more!",
                    "Мяу!", "Meow!"), 2.2f);
        }
    }

    private void doFeed() {
        touch();
        wake(false);
        if (hunger >= 95f) {
            refuse(tr("Не хочу, я сытый!", "No thanks, I'm full!"),
                    tr("Меня сейчас стошнит… 🤢", "I'm going to be sick… 🤢"));
            return;
        }
        hunger = clamp100(hunger + 35f);
        mood = clamp100(mood + 4f);
        eatT = 2.2f;
        jump(8f);
        float cx = wPx / 2f, cy = hPx / 2f;
        burst("🍖", 3, cx, cy + 0.3f * r, 40f, 60f, 16f);
        react("😋", F_HAPPY, 2.6f);
        say(pick("Ням-ням! Вкусно! 😋", "Yum yum! Delicious! 😋",
                "Спасибо за еду!", "Thanks for the food!",
                "Мммм, вкуснятина!", "Mmm, tasty!"), 2.6f);
    }

    private void doWater() {
        touch();
        wake(false);
        if (thirst >= 95f) {
            refuse(tr("Я не хочу пить!", "I'm not thirsty!"),
                    tr("Я сейчас лопну… 🤢", "I'm going to burst… 🤢"));
            return;
        }
        thirst = clamp100(thirst + 40f);
        mood = clamp100(mood + 3f);
        drinkT = 2.2f;
        jump(6f);
        float cx = wPx / 2f, cy = hPx / 2f;
        burst("💧", 4, cx, cy + 0.2f * r, 40f, 50f, 15f);
        react("😌", F_HAPPY, 2.6f);
        say(pick("Буль-буль… Спасибо! 💧", "Glug glug… Thanks! 💧",
                "Ах, свежо!", "Ahh, refreshing!"), 2.6f);
    }

    private void refuse(String firstText, String sickText) {
        long now = SystemClock.uptimeMillis();
        refusals.add(now);
        for (int i = refusals.size() - 1; i >= 0; i--) {
            if (now - refusals.get(i) > 15000L) {
                refusals.remove(i);
            }
        }
        shakeT = 0.6f;
        if (refusals.size() >= 3) {
            refusals.clear();
            mood = clamp100(mood - 8f);
            say(sickText, 3.5f);
            react("🤢", F_SAD, 3.5f);
        } else {
            mood = clamp100(mood - 1f);
            say(firstText, 2.5f);
            react("🙅", F_ANGRY, 2.2f);
        }
    }

    private void doPlay() {
        touch();
        wake(false);
        if (hunger < 12f) {
            shakeT = 0.4f;
            say(tr("Сначала покорми меня! 🍖", "Feed me first! 🍖"), 3f);
            react("🥺", F_SAD, 3f);
            return;
        }
        if (thirst < 12f) {
            shakeT = 0.4f;
            say(tr("Пить хочу… сначала воды 💧", "I'm too thirsty… water first 💧"), 3f);
            react("🥺", F_SAD, 3f);
            return;
        }
        if (energy < 15f) {
            shakeT = 0.4f;
            say(tr("Я устал… дай поспать 😴", "I'm tired… let me sleep 😴"), 3f);
            react("🥱", F_SAD, 3f);
            return;
        }
        mood = clamp100(mood + 18f);
        energy = clamp100(energy - 14f);
        hunger = clamp100(hunger - 7f);
        thirst = clamp100(thirst - 7f);
        playT = 3.2f;
        wiggleT = 1f;
        jump(16f);
        float cx = wPx / 2f, cy = hPx / 2f;
        burst("⭐", 5, cx, cy - 0.5f * r, 90f, 80f, 16f);
        react("🤩", F_HAPPY, 3.2f);
        say(pick("Ура, играем! 🎾", "Yay, let's play! 🎾",
                "Лови-лови!", "Catch it, catch it!",
                "Это так весело!", "This is so fun!"), 3f);
    }

    private void doSleep() {
        touch();
        if (sleeping) {
            wake(true);
            return;
        }
        if (energy >= 95f) {
            shakeT = 0.5f;
            say(tr("Не хочу спать, я бодр! 💪", "I'm not sleepy, I'm full of energy! 💪"), 3f);
            react("😎", F_NORMAL, 3f);
            return;
        }
        sleeping = true;
        menuOpen = false;
        say(tr("Спокойной ночи… 💤", "Good night… 💤"), 3f);
    }

    private void doAvatar() {
        touch();
        boolean ok = false;
        try {
            ok = captureAvatar();
        } catch (Throwable e) {
            logger.log("pet: avatar capture failed: " + e);
        }
        if (ok) {
            jump(12f);
            wiggleT = 0.8f;
            burst("✨", 5, wPx / 2f, hPx / 2f - 0.5f * r, 80f, 60f, 16f);
            react("😎", F_HAPPY, 3f);
            say(tr("Теперь я выгляжу как ты!", "Now I look just like you!"), 3.5f);
        } else {
            shakeT = 0.5f;
            say(tr("Не вижу аватарку. Открой свой профиль и нажми 🖼 ещё раз",
                    "I can't see the avatar. Open your profile and tap 🖼 again"), 5.5f);
            react("🤔", F_SURPRISE, 3f);
        }
    }

    private void onButton(int b) {
        switch (b) {
            case BTN_FEED:
                doFeed();
                break;
            case BTN_WATER:
                doWater();
                break;
            case BTN_PLAY:
                doPlay();
                break;
            case BTN_SLEEP:
                doSleep();
                break;
            case BTN_AVATAR:
                doAvatar();
                break;
            default:
                break;
        }
    }

    private void toggleMenu() {
        menuOpen = !menuOpen;
        if (menuOpen) {
            updateFlip();
            touch();
        }
    }

    // ------------------------------------------------------------------ avatar

    private boolean captureAvatar() {
        if (host == null) {
            return false;
        }
        final ArrayList<View> candidates = new ArrayList<View>();
        collect(host, candidates);
        Collections.sort(candidates, new Comparator<View>() {
            @Override
            public int compare(View a, View b) {
                return b.getWidth() - a.getWidth();
            }
        });
        for (int i = 0; i < candidates.size(); i++) {
            Bitmap b = snapshot(candidates.get(i));
            if (b != null && !isBlank(b)) {
                setAvatar(b, true);
                return true;
            }
        }
        return false;
    }

    private void collect(View v, ArrayList<View> out) {
        if (v == this || v.getVisibility() != View.VISIBLE || v.getAlpha() < 0.5f) {
            return;
        }
        if (v instanceof ImageView) {
            int w = v.getWidth();
            int h = v.getHeight();
            if (w >= 64f * d && w <= 170f * d && Math.abs(w - h) <= 3f * d) {
                int[] loc = new int[2];
                v.getLocationOnScreen(loc);
                int screenH = getResources().getDisplayMetrics().heightPixels;
                if (loc[1] < screenH * 0.5f && v.getGlobalVisibleRect(new Rect())) {
                    out.add(v);
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collect(g.getChildAt(i), out);
            }
        }
    }

    private Bitmap snapshot(View v) {
        try {
            int w = v.getWidth();
            int h = v.getHeight();
            if (w <= 0 || h <= 0) {
                return null;
            }
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            v.draw(new Canvas(b));
            return Bitmap.createScaledBitmap(b, 256, 256, true);
        } catch (Throwable e) {
            return null;
        }
    }

    private boolean isBlank(Bitmap b) {
        int first = 0;
        boolean seenFirst = false;
        for (int iy = 1; iy <= 5; iy++) {
            for (int ix = 1; ix <= 5; ix++) {
                int c = b.getPixel(b.getWidth() * ix / 6, b.getHeight() * iy / 6);
                if ((c >>> 24) < 16) {
                    continue;
                }
                if (!seenFirst) {
                    first = c;
                    seenFirst = true;
                } else if (c != first) {
                    return false;
                }
            }
        }
        return true;
    }

    private void setAvatar(Bitmap b, boolean persist) {
        avatar = b;
        avatarShader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        try {
            Bitmap one = Bitmap.createScaledBitmap(b, 1, 1, true);
            int c = one.getPixel(0, 0) | 0xFF000000;
            bodyColor = c;
            bodyDark = darker(c, 0.78f);
            headColor = lighter(c, 0.3f);
        } catch (Throwable ignored) {
            // keep the defaults
        }
        if (persist) {
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(avatarFile);
                b.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (Throwable e) {
                logger.log("pet: avatar save failed: " + e);
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (Throwable ignored) {
                        // nothing to do
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ touch

    private float btnX(int i) {
        return wPx / 2f + (i - (BTN_COUNT - 1) / 2f) * 56f * d;
    }

    private float btnY() {
        return hPx / 2f + (flip ? -96f : 100f) * d;
    }

    private int hitButton(float x, float y) {
        for (int i = 0; i < BTN_COUNT; i++) {
            float dx = x - btnX(i);
            float dy = y - btnY();
            if (dx * dx + dy * dy <= (26f * d) * (26f * d)) {
                return i;
            }
        }
        return -1;
    }

    private boolean hitPet(float x, float y) {
        float cx = wPx / 2f, cy = hPx / 2f;
        return Math.abs(x - cx) < 1.15f * r && y > cy - 1.4f * r && y < cy + 1.8f * r;
    }

    private void lockParent() {
        ViewParent p = getParent();
        if (p != null) {
            p.requestDisallowInterceptTouchEvent(true);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX();
        float y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mode = 0;
                downBtn = -1;
                pressedBtn = -1;
                if (menuOpen) {
                    int b = hitButton(x, y);
                    if (b >= 0) {
                        mode = 2;
                        downBtn = b;
                        pressedBtn = b;
                        touch();
                        lockParent();
                        return true;
                    }
                }
                if (hitPet(x, y)) {
                    mode = 1;
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startPx = px;
                    startPy = py;
                    moved = false;
                    longFired = false;
                    postDelayed(longPress, 450L);
                    touch();
                    lockParent();
                    return true;
                }
                return false;

            case MotionEvent.ACTION_MOVE:
                if (mode == 1) {
                    float dx = e.getRawX() - downX;
                    float dy = e.getRawY() - downY;
                    if (!moved && Math.hypot(dx, dy) > slop) {
                        moved = true;
                        removeCallbacks(longPress);
                        startDrag();
                    }
                    if (moved) {
                        px = startPx + dx;
                        py = startPy + dy;
                        clampPos();
                        applyTranslation();
                    }
                    return true;
                }
                if (mode == 2) {
                    pressedBtn = hitButton(x, y) == downBtn ? downBtn : -1;
                    return true;
                }
                return false;

            case MotionEvent.ACTION_UP:
                if (mode == 1) {
                    removeCallbacks(longPress);
                    if (moved) {
                        endDrag();
                    } else if (!longFired) {
                        doPet();
                    }
                } else if (mode == 2) {
                    if (downBtn >= 0 && hitButton(x, y) == downBtn) {
                        onButton(downBtn);
                    }
                }
                mode = 0;
                pressedBtn = -1;
                return true;

            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                if (mode == 1 && moved) {
                    endDrag();
                }
                mode = 0;
                pressedBtn = -1;
                return true;

            default:
                return mode != 0;
        }
    }

    private void startDrag() {
        dragging = true;
        if (sleeping) {
            wake(false);
            say(tr("Ой, куда ты меня несёшь?!", "Hey, where are you taking me?!"), 3f);
        } else {
            say(pick("Вииии! 🎈", "Wheee! 🎈",
                    "Ой-ой, высоко!", "Oh, it's high up here!",
                    "Поехали!", "Off we go!"), 2.5f);
        }
    }

    private void endDrag() {
        dragging = false;
        updateFlip();
        squashT = 0.28f;
        jump(5f);
        react("😅", F_HAPPY, 2f);
        say(pick("Приземлился! Здесь мило", "Landed! Nice spot",
                "Ух! Новое местечко", "Whew! A new spot"), 2.5f);
        save();
    }

    // ------------------------------------------------------------------ drawing

    private int currentFace() {
        if (sleeping) return F_SLEEP;
        if (dragging) return F_SURPRISE;
        if (t < emoUntil) return emoFace;
        if (minStat() < 20f) return F_SAD;
        if (mood > 75f && hunger > 35f && thirst > 35f) return F_HAPPY;
        if (mood < 30f) return F_SAD;
        return F_NORMAL;
    }

    private String currentBadge(int face) {
        if (sleeping) return "😴";
        if (dragging) return "😮";
        if (t < emoUntil && emoBadge.length() > 0) return emoBadge;
        switch (face) {
            case F_HAPPY:
                return "😊";
            case F_SAD:
                return "😢";
            case F_ANGRY:
                return "😠";
            case F_SURPRISE:
                return "😮";
            case F_SLEEP:
                return "😴";
            default:
                return "🙂";
        }
    }

    private void emoji(Canvas c, String s, float x, float y, float size) {
        text.setTextSize(size);
        c.drawText(s, x, y + size * 0.35f, text);
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = wPx / 2f;
        float cy = hPx / 2f;
        float R = r;
        int face = currentFace();

        // ground shadow
        float shadowK = 1f - Math.min(0.5f, (jumpH + lift) / (R * 3f));
        fill.setColor(0x33000000);
        rect.set(cx - 0.8f * R * shadowK, cy + 1.62f * R, cx + 0.8f * R * shadowK, cy + 1.9f * R);
        c.drawOval(rect, fill);

        // pet
        float bob = (float) Math.sin(t * (sleeping ? 1.4f : 2.4f)) * (sleeping ? 1.2f : 2.2f) * d;
        float shakeX = shakeT > 0f ? (float) Math.sin(t * 60f) * 6f * d * Math.min(1f, shakeT * 2f) : 0f;
        float tilt = 0f;
        if (dragging) {
            tilt = (float) Math.sin(t * 9f) * 7f;
        } else if (wiggleT > 0f) {
            tilt = (float) Math.sin(t * 28f) * 9f * Math.min(1f, wiggleT * 3f);
        } else if (face == F_SAD) {
            tilt = -4f;
        }
        float sx = 1f;
        float sy = 1f;
        if (squashT > 0f) {
            float k = squashT / 0.28f;
            sx = 1f + 0.14f * k;
            sy = 1f - 0.14f * k;
        } else if (dragging) {
            sx = 0.95f;
            sy = 1.08f;
        }
        sy *= 1f + (float) Math.sin(t * 2.4f) * 0.012f;

        c.save();
        c.translate(cx + shakeX, cy + bob - jumpH - lift);
        c.rotate(tilt, 0f, 1.6f * R);
        c.scale(sx, sy, 0f, 1.7f * R);
        drawPet(c, R, face);
        c.restore();

        // particles
        for (int i = 0; i < parts.size(); i++) {
            Particle p = parts.get(i);
            text.setAlpha((int) (255f * Math.min(1f, p.life / (p.max * 0.5f))));
            emoji(c, p.s, p.x, p.y, p.size);
            text.setAlpha(255);
        }

        if (menuOpen) {
            drawMenu(c);
            drawStats(c);
        }
        drawBubble(c, cx, cy);
    }

    private void drawPet(Canvas c, float R, int face) {
        boolean happy = face == F_HAPPY;

        // tail
        float wagSpeed = happy ? 9f : (face == F_SAD || sleeping ? 1.2f : 3.5f);
        float wagAmp = happy ? 0.3f : (face == F_SAD || sleeping ? 0.06f : 0.16f);
        float wag = (float) Math.sin(t * wagSpeed) * wagAmp * R;
        stroke.setColor(bodyDark);
        stroke.setStrokeWidth(0.2f * R);
        path.reset();
        path.moveTo(0.5f * R, 1.25f * R);
        path.quadTo(1.3f * R, 1.3f * R, 1.15f * R + wag, 0.55f * R);
        c.drawPath(path, stroke);

        // body
        fill.setColor(bodyColor);
        rect.set(-0.72f * R, 0.35f * R, 0.72f * R, 1.6f * R);
        c.drawOval(rect, fill);
        fill.setColor(0x66FFFFFF);
        rect.set(-0.4f * R, 0.7f * R, 0.4f * R, 1.5f * R);
        c.drawOval(rect, fill);

        // feet
        float leg = dragging ? (float) Math.sin(t * 10f) * 0.12f * R : 0f;
        fill.setColor(bodyDark);
        rect.set(-0.65f * R, 1.5f * R + leg, -0.11f * R, 1.84f * R + leg);
        c.drawOval(rect, fill);
        rect.set(0.11f * R, 1.5f * R - leg, 0.65f * R, 1.84f * R - leg);
        c.drawOval(rect, fill);

        // arms
        float armL = 0.95f * R;
        float armR = 0.95f * R;
        if (dragging) {
            armL = 0.5f * R + (float) Math.sin(t * 10f) * 0.1f * R;
            armR = 0.5f * R - (float) Math.sin(t * 10f) * 0.1f * R;
        } else if (happy) {
            armR = 0.95f * R - (0.5f + 0.5f * (float) Math.sin(t * 8f)) * 0.5f * R;
        }
        fill.setColor(bodyDark);
        c.drawCircle(-0.8f * R, armL, 0.19f * R, fill);
        c.drawCircle(0.8f * R, armR, 0.19f * R, fill);

        // ears
        for (int s = -1; s <= 1; s += 2) {
            fill.setColor(bodyDark);
            path.reset();
            path.moveTo(s * 0.85f * R, -0.15f * R);
            path.lineTo(s * 0.62f * R, -1.4f * R);
            path.lineTo(s * 0.12f * R, -0.85f * R);
            path.close();
            c.drawPath(path, fill);
            fill.setColor(0xAAFFB6C1);
            path.reset();
            path.moveTo(s * 0.72f * R, -0.35f * R);
            path.lineTo(s * 0.62f * R, -1.05f * R);
            path.lineTo(s * 0.32f * R, -0.8f * R);
            path.close();
            c.drawPath(path, fill);
        }

        // head ring
        fill.setColor(0xFFFFFFFF);
        c.drawCircle(0f, 0f, R + 2.5f * d, fill);

        // head
        if (avatarShader != null && avatar != null) {
            float k = 2f * R / avatar.getWidth();
            matrix.reset();
            matrix.setScale(k, k);
            matrix.postTranslate(-R, -R);
            avatarShader.setLocalMatrix(matrix);
            fill.setShader(avatarShader);
            c.drawCircle(0f, 0f, R, fill);
            fill.setShader(null);
        } else {
            fill.setColor(headColor);
            c.drawCircle(0f, 0f, R, fill);
            drawFace(c, R, face);
        }
        if (sleeping) {
            fill.setColor(0x66000000);
            c.drawCircle(0f, 0f, R, fill);
        }

        // food / drink / ball
        if (eatT > 0f) {
            float s = R * 0.8f * Math.min(1f, eatT);
            emoji(c, "🍖", 0f, 0.62f * R + (float) Math.sin(t * 14f) * 0.05f * R, s);
        }
        if (drinkT > 0f) {
            float s = R * 0.8f * Math.min(1f, drinkT);
            emoji(c, "🥛", 0f, 0.62f * R + (float) Math.sin(t * 12f) * 0.04f * R, s);
        }
        if (playT > 0f) {
            float bx = (float) Math.cos(t * 5f) * 1.45f * R;
            float by = 0.9f * R - Math.abs((float) Math.sin(t * 5f)) * 1.4f * R;
            emoji(c, "🎾", bx, by, R * 0.5f);
        }

        // badge
        fill.setColor(0xEEFFFFFF);
        c.drawCircle(1.0f * R, -0.72f * R, 0.34f * R, fill);
        emoji(c, currentBadge(face), 1.0f * R, -0.72f * R, 0.5f * R);
    }

    private void drawFace(Canvas c, float R, int face) {
        stroke.setColor(0xFF3A2A1E);
        stroke.setStrokeWidth(0.075f * R);
        fill.setColor(0xFF3A2A1E);
        float er = 0.1f * R;
        float ey = -0.12f * R;
        boolean blinking = blinkT < 0f;

        for (int s = -1; s <= 1; s += 2) {
            float ex = s * 0.36f * R;
            switch (face) {
                case F_SLEEP:
                    rect.set(ex - 1.4f * er, ey - er, ex + 1.4f * er, ey + er);
                    c.drawArc(rect, 0f, 180f, false, stroke);
                    break;
                case F_HAPPY:
                    rect.set(ex - 1.4f * er, ey - er * 0.2f, ex + 1.4f * er, ey + er * 2f);
                    c.drawArc(rect, 180f, 180f, false, stroke);
                    break;
                case F_SURPRISE:
                    c.drawCircle(ex, ey, er * 1.35f, fill);
                    break;
                case F_ANGRY:
                    c.drawCircle(ex, ey, er, fill);
                    c.drawLine(s * 0.55f * R, -0.4f * R, s * 0.2f * R, -0.27f * R, stroke);
                    break;
                case F_SAD:
                    c.drawCircle(ex, ey + 0.03f * R, er * 1.1f, fill);
                    c.drawLine(s * 0.55f * R, -0.3f * R, s * 0.2f * R, -0.4f * R, stroke);
                    break;
                default:
                    if (blinking) {
                        c.drawLine(ex - er, ey, ex + er, ey, stroke);
                    } else {
                        c.drawCircle(ex, ey, er, fill);
                    }
                    break;
            }
        }

        // cheeks
        if (face == F_HAPPY) {
            fill.setColor(0x66FF6B81);
            c.drawCircle(-0.62f * R, 0.28f * R, 0.13f * R, fill);
            c.drawCircle(0.62f * R, 0.28f * R, 0.13f * R, fill);
        }

        // nose
        fill.setColor(0xFFE88A9A);
        c.drawCircle(0f, 0.2f * R, 0.06f * R, fill);

        // mouth
        stroke.setColor(0xFF3A2A1E);
        boolean chewing = eatT > 0f || drinkT > 0f;
        if (chewing || face == F_SURPRISE) {
            float open = chewing ? 0.1f + 0.08f * (float) Math.abs(Math.sin(t * 12f)) : 0.14f;
            fill.setColor(0xFF3A2A1E);
            rect.set(-0.12f * R, 0.32f * R, 0.12f * R, (0.32f + open * 2f) * R);
            c.drawOval(rect, fill);
            return;
        }
        switch (face) {
            case F_HAPPY:
                rect.set(-0.28f * R, 0.12f * R, 0.28f * R, 0.52f * R);
                c.drawArc(rect, 10f, 160f, false, stroke);
                break;
            case F_SAD:
                rect.set(-0.2f * R, 0.42f * R, 0.2f * R, 0.7f * R);
                c.drawArc(rect, 190f, 160f, false, stroke);
                break;
            case F_ANGRY:
                c.drawLine(-0.18f * R, 0.5f * R, 0.18f * R, 0.5f * R, stroke);
                break;
            case F_SLEEP:
                rect.set(-0.1f * R, 0.35f * R, 0.1f * R, 0.5f * R);
                c.drawArc(rect, 0f, 180f, false, stroke);
                break;
            default:
                rect.set(-0.18f * R, 0.2f * R, 0.18f * R, 0.5f * R);
                c.drawArc(rect, 20f, 140f, false, stroke);
                break;
        }
    }

    private void drawMenu(Canvas c) {
        String[] icons = {"🍖", "💧", "🎾", sleeping ? "☀️" : "😴", "🖼️"};
        String[] labels = ru
                ? new String[]{"Кормить", "Поить", "Играть", sleeping ? "Будить" : "Спать", "Аватар"}
                : new String[]{"Feed", "Water", "Play", sleeping ? "Wake" : "Sleep", "Avatar"};
        float by = btnY();
        for (int i = 0; i < BTN_COUNT; i++) {
            float bx = btnX(i);
            float rad = 24f * d * (pressedBtn == i ? 0.9f : 1f);
            fill.setColor(0x33000000);
            c.drawCircle(bx, by + 2f * d, rad + 1f * d, fill);
            fill.setColor(pressedBtn == i ? 0xFFFFE9B8 : 0xEEFFFFFF);
            c.drawCircle(bx, by, rad, fill);
            emoji(c, icons[i], bx, by, 22f * d);

            text.setTextSize(10f * d);
            text.setColor(0xFFFFFFFF);
            text.setShadowLayer(2f * d, 0f, 0f, 0xFF000000);
            c.drawText(labels[i], bx, by + 24f * d + 12f * d, text);
            text.setShadowLayer(0f, 0f, 0f, 0);
            text.setColor(0xFF000000);
        }
    }

    private void drawStats(Canvas c) {
        float cx = wPx / 2f;
        float y = hPx / 2f + (flip ? -134f : 136f) * d;
        fill.setColor(0x88000000);
        rect.set(cx - 140f * d, y - 11f * d, cx + 140f * d, y + 11f * d);
        c.drawRoundRect(rect, 11f * d, 11f * d, fill);

        String[] icons = {"🍖", "💧", "💗", "⚡"};
        float[] vals = {hunger, thirst, mood, energy};
        for (int i = 0; i < 4; i++) {
            float sx = cx - 132f * d + i * 66f * d;
            emoji(c, icons[i], sx + 7f * d, y, 12f * d);
            float bx = sx + 17f * d;
            float bw = 40f * d;
            fill.setColor(0x55FFFFFF);
            rect.set(bx, y - 2.5f * d, bx + bw, y + 2.5f * d);
            c.drawRoundRect(rect, 2.5f * d, 2.5f * d, fill);
            float v = vals[i];
            fill.setColor(v > 50f ? 0xFF4CD964 : (v > 25f ? 0xFFFFB020 : 0xFFFF4D4D));
            rect.set(bx, y - 2.5f * d, bx + bw * v / 100f, y + 2.5f * d);
            c.drawRoundRect(rect, 2.5f * d, 2.5f * d, fill);
        }
    }

    private boolean bubbleAbove() {
        if (menuOpen) {
            return !flip;
        }
        return !(host != null && host.getHeight() > 0 && py < host.getHeight() * 0.25f);
    }

    private void drawBubble(Canvas c, float cx, float cy) {
        if (bubbleText == null || t > bubbleUntil) {
            return;
        }
        if (bubbleLayout == null) {
            int maxW = (int) (210f * d);
            int w = (int) Math.min(maxW, Math.ceil(Layout.getDesiredWidth(bubbleText, bubblePaint)) + 1);
            bubbleLayout = new StaticLayout(bubbleText, bubblePaint, w,
                    Layout.Alignment.ALIGN_CENTER, 1f, 0f, false);
        }
        float bw = bubbleLayout.getWidth() + 20f * d;
        float bh = bubbleLayout.getHeight() + 14f * d;
        boolean above = bubbleAbove();
        float bx = cx - bw / 2f;
        float by = above ? cy - 58f * d - bh : cy + 74f * d;

        fill.setColor(0xF2FFFFFF);
        rect.set(bx, by, bx + bw, by + bh);
        c.drawRoundRect(rect, 14f * d, 14f * d, fill);
        path.reset();
        if (above) {
            path.moveTo(cx - 7f * d, by + bh - 1f);
            path.lineTo(cx + 7f * d, by + bh - 1f);
            path.lineTo(cx, by + bh + 9f * d);
        } else {
            path.moveTo(cx - 7f * d, by + 1f);
            path.lineTo(cx + 7f * d, by + 1f);
            path.lineTo(cx, by - 9f * d);
        }
        path.close();
        c.drawPath(path, fill);

        c.save();
        c.translate(bx + 10f * d, by + 7f * d);
        bubbleLayout.draw(c);
        c.restore();
    }
}
