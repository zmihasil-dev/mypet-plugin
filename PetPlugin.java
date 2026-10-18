package pet.margyt;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import java.io.File;

import cat.narezany.margyt.plugin.MargyPlugin;

/**
 * Pet plugin: a draggable pet drawn on top of every TikTok screen
 * (feed, profile, anything else). The pet wears the user's avatar.
 */
public final class PetPlugin extends MargyPlugin {

    private final Handler main = new Handler(Looper.getMainLooper());
    private PetView pet;

    @Override
    public void onStart(Context context) {
        margyt().log("pet: started");
    }

    @Override
    public void onActivityResumed(Activity activity) {
        try {
            attach(activity);
        } catch (Throwable t) {
            margyt().log("pet: attach failed: " + t);
        }
    }

    @Override
    public void onActivityPaused(Activity activity) {
        try {
            if (pet != null) {
                pet.detachFrom(activity.getWindow().getDecorView());
            }
        } catch (Throwable t) {
            margyt().log("pet: detach failed: " + t);
        }
    }

    @Override
    public void onStop() {
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (pet != null) {
                        pet.destroy();
                        pet = null;
                    }
                } catch (Throwable t) {
                    margyt().log("pet: stop failed: " + t);
                }
            }
        });
    }

    private void attach(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) {
            return;
        }
        if (pet == null) {
            Context app = margyt().context();
            File avatarFile = new File(app.getFilesDir(), "margyt_pet_avatar.png");
            pet = new PetView(app, margyt().prefs(), avatarFile, new PetView.Logger() {
                @Override
                public void log(String line) {
                    margyt().log(line);
                }
            });
        }
        pet.attachTo((ViewGroup) decor);
    }
}
