package com.kamran.homecamlive;

import android.app.Activity;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

/** Edge-to-edge feed overlay with a CLOSE button. */
public class FullscreenView {
    private View root;
    private ImageView image;

    public void show(Activity activity, Bitmap bitmap) {
        if (root != null) { setBitmap(bitmap); return; }
        root = LayoutInflater.from(activity).inflate(R.layout.view_fullscreen, null);
        image = root.findViewById(R.id.fsImage);
        setBitmap(bitmap);
        root.findViewById(R.id.fsClose).setOnClickListener(v -> hide());
        FrameLayout decor = activity.getWindow().getDecorView().findViewById(android.R.id.content);
        decor.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    public void setBitmap(Bitmap bitmap) {
        if (image != null && bitmap != null) image.setImageBitmap(bitmap);
    }

    public void hide() {
        if (root != null && root.getParent() != null) {
            ((android.view.ViewGroup) root.getParent()).removeView(root);
        }
        root = null;
        image = null;
    }

    public boolean isShowing() { return root != null; }
}
