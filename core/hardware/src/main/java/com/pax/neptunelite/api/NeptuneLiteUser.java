package com.pax.neptunelite.api;

import android.content.Context;
import com.pax.dal.IDAL;

public class NeptuneLiteUser {
    private static NeptuneLiteUser instance;
    private NeptuneLiteUser() {}
    public static NeptuneLiteUser getInstance() {
        if (instance == null) instance = new NeptuneLiteUser();
        return instance;
    }
    public IDAL getDal(Context context) { return null; }
}
