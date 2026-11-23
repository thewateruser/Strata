package shots;

import static org.robolectric.Shadows.shadowOf;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import app.strata.psd.MainActivity;
import app.strata.psd.ViewerActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 36)
public class Shots {
  static final String OUT = System.getProperty("shots.out", "/tmp/shots");

  static void idle(long ms) {
    long end = System.currentTimeMillis() + ms;
    while (System.currentTimeMillis() < end) {
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
      try { Thread.sleep(4); } catch (InterruptedException e) {}
    }
  }

  static void until(BooleanSupplier cond, long timeoutMs) {
    long end = System.currentTimeMillis() + timeoutMs;
    while (!cond.getAsBoolean() && System.currentTimeMillis() < end) idle(50);
  }

  static void snap(Activity a, String name) throws Exception {
    View v = a.getWindow().getDecorView();
    Bitmap b = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
    v.draw(new Canvas(b));
    new File(OUT).mkdirs();
    try (FileOutputStream o = new FileOutputStream(new File(OUT, name))) { b.compress(Bitmap.CompressFormat.PNG, 100, o); }
  }

  static View find(View root, String desc) {
    if (desc.contentEquals(String.valueOf(root.getContentDescription()))) return root;
    if (root instanceof android.view.ViewGroup) {
      android.view.ViewGroup g = (android.view.ViewGroup) root;
      for (int i = 0; i < g.getChildCount(); i++) { View f = find(g.getChildAt(i), desc); if (f != null) return f; }
    }
    return null;
  }

  @Test @Config(qualifiers = "w412dp-h915dp-xxhdpi")
  public void homeEmpty() throws Exception {
    ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
    idle(1500);
    snap(c.get(), "home_light.png");
  }

  @Test @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
  public void homeDark() throws Exception {
    ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
    idle(1500);
    snap(c.get(), "home_dark.png");
  }

  @Test @Config(qualifiers = "w412dp-h915dp-xxhdpi")
  public void viewer() throws Exception {
    File f = File.createTempFile("sample", ".psd");
    Files.copy(new File(System.getProperty("sample.psd")).toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    Intent i = new Intent().putExtra(ViewerActivity.EXTRA_PATH, f.getPath());
    ActivityController<ViewerActivity> c = Robolectric.buildActivity(ViewerActivity.class, i).setup();
    ViewerActivity a = c.get();
    idle(300);
    snap(a, "viewer_loading.png");
    until(() -> find(a.getWindow().getDecorView(), "Layers").getAlpha() >= 1f, 30000);
    idle(1500);
    snap(a, "viewer.png");
    find(a.getWindow().getDecorView(), "Layers").performClick();
    idle(2500);
    snap(a, "viewer_layers.png");
    find(a.getWindow().getDecorView(), "Document info").performClick();
    idle(1500);
    snap(a, "viewer_info.png");
  }

  @Test @Config(qualifiers = "w412dp-h915dp-night-xxhdpi")
  public void viewerDarkLayers() throws Exception {
    File f = File.createTempFile("sample", ".psd");
    Files.copy(new File(System.getProperty("sample.psd")).toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    Intent i = new Intent().putExtra(ViewerActivity.EXTRA_PATH, f.getPath());
    ActivityController<ViewerActivity> c = Robolectric.buildActivity(ViewerActivity.class, i).setup();
    ViewerActivity a = c.get();
    until(() -> find(a.getWindow().getDecorView(), "Layers").getAlpha() >= 1f, 30000);
    idle(800);
    find(a.getWindow().getDecorView(), "Layers").performClick();
    idle(2500);
    // Hide the "Mountains" group via its eye toggle (2nd row's toggle).
    View root = a.getWindow().getDecorView();
    java.util.List<View> eyes = new java.util.ArrayList<>();
    collect(root, "Toggle visibility", eyes);
    if (eyes.size() > 2) eyes.get(2).performClick();
    idle(3000);
    snap(a, "viewer_dark_layers_toggled.png");
  }

  static void collect(View root, String desc, java.util.List<View> out) {
    if (desc.contentEquals(String.valueOf(root.getContentDescription()))) out.add(root);
    if (root instanceof android.view.ViewGroup) {
      android.view.ViewGroup g = (android.view.ViewGroup) root;
      for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), desc, out);
    }
  }
}
