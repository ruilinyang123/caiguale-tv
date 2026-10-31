package com.fongmi.android.tv.impl;

import android.view.View;

public interface UpdateListener {

    void onCancel(View view);

    void onConfirm(View view);

    /**
     * Hand the download off to the browser: copy the link and open it, so the user
     * is not stuck watching an in-app download. Wired up on phones only — a TV has
     * no browser to hand off to.
     */
    void onBrowser(View view);
}
