/*
 * QRV-Hub - single-server push-to-talk client for the Spender TC-7G.
 * Copyright (C) 2026 Narun Chanyavilas <opensource@narun.me>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */
package me.narun.apps.qrvhub;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Rows for the members page, the channels page and the menu card. */
final class ItemAdapter extends BaseAdapter {
    static final int MEMBER = 0, CHANNEL = 1, MENU = 2;

    static final class Item {
        final int type;
        CharSequence text;
        boolean me, talking;          // member
        int depth, count;             // channel
        boolean here, listening;      // channel
        int icon, iconTint;           // menu

        private Item(int type, CharSequence text) {
            this.type = type;
            this.text = text;
        }

        static Item member(String name, boolean me, boolean talking) {
            Item i = new Item(MEMBER, name);
            i.me = me;
            i.talking = talking;
            return i;
        }

        static Item channel(String name, int depth, int count, boolean here, boolean listening) {
            Item i = new Item(CHANNEL, name);
            i.depth = depth;
            i.count = count;
            i.here = here;
            i.listening = listening;
            return i;
        }

        static Item menu(CharSequence label, int icon, int iconTint) {
            Item i = new Item(MENU, label);
            i.icon = icon;
            i.iconTint = iconTint;
            return i;
        }
    }

    /** Avatar colours, picked by a stable hash of the name. */
    private static final int[] AVATAR = {
            0xFF00897B, 0xFF5E35B1, 0xFFD84315, 0xFF3949AB, 0xFFAD1457, 0xFF00838F, 0xFF6D4C41, 0xFF558B2F };

    private final Context mContext;
    private final LayoutInflater mInflater;
    private final float mDensity;
    private List<Item> mItems = new ArrayList<>();

    ItemAdapter(Context c) {
        mContext = c;
        mInflater = LayoutInflater.from(c);
        mDensity = c.getResources().getDisplayMetrics().density;
    }

    void set(List<Item> items) {
        mItems = items;
        notifyDataSetChanged();
    }

    @Override public int getCount() { return mItems.size(); }
    @Override public Object getItem(int position) { return mItems.get(position); }
    @Override public long getItemId(int position) { return position; }
    @Override public int getViewTypeCount() { return 3; }
    @Override public int getItemViewType(int position) { return mItems.get(position).type; }

    // Member rows are not selectable: there is nothing to do with a member, and
    // without a selected row the list keeps its scroll position on refresh.
    @Override public boolean areAllItemsEnabled() { return false; }
    @Override public boolean isEnabled(int position) { return mItems.get(position).type != MEMBER; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Item it = mItems.get(position);
        switch (it.type) {
            case MEMBER: return member(it, convertView, parent);
            case CHANNEL: return channel(it, convertView, parent);
            default: return menu(it, convertView, parent);
        }
    }

    private View member(Item it, View v, ViewGroup parent) {
        if (v == null) v = mInflater.inflate(R.layout.row_member, parent, false);
        String name = it.text.toString();
        TextView avatar = v.findViewById(R.id.avatar);
        avatar.setText(initials(name));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(AVATAR[(name.hashCode() & 0x7fffffff) % AVATAR.length]);
        avatar.setBackground(circle);
        TextView tv = v.findViewById(R.id.name);
        tv.setText(name);
        tv.setTextColor(mContext.getResources().getColor(it.talking ? R.color.green_l : R.color.text));
        tv.setTypeface(null, it.talking ? Typeface.BOLD : Typeface.NORMAL);
        v.findViewById(R.id.you).setVisibility(it.me ? View.VISIBLE : View.GONE);
        v.findViewById(R.id.wave).setVisibility(it.talking ? View.VISIBLE : View.GONE);
        return v;
    }

    private View channel(Item it, View v, ViewGroup parent) {
        if (v == null) v = mInflater.inflate(R.layout.row_channel, parent, false);
        View indent = v.findViewById(R.id.indent);
        ViewGroup.LayoutParams lp = indent.getLayoutParams();
        lp.width = Math.round(it.depth * 14 * mDensity);
        indent.setLayoutParams(lp);
        v.findViewById(R.id.here).setVisibility(it.here ? View.VISIBLE : View.GONE);
        TextView tv = v.findViewById(R.id.name);
        tv.setText(it.text);
        tv.setTextColor(mContext.getResources().getColor(it.count > 0 || it.here ? R.color.text : R.color.sub));
        tv.setTypeface(null, it.here ? Typeface.BOLD : Typeface.NORMAL);
        v.findViewById(R.id.listen).setVisibility(it.listening ? View.VISIBLE : View.GONE);
        TextView count = v.findViewById(R.id.count);
        count.setVisibility(it.count > 0 ? View.VISIBLE : View.GONE);
        count.setText(String.valueOf(it.count));
        return v;
    }

    private View menu(Item it, View v, ViewGroup parent) {
        if (v == null) v = mInflater.inflate(R.layout.row_menu, parent, false);
        ImageView icon = v.findViewById(R.id.icon);
        icon.setImageResource(it.icon);
        icon.setColorFilter(it.iconTint);
        ((TextView) v.findViewById(R.id.label)).setText(it.text);
        return v;
    }

    /** "XX9ABC-1" -> "X1", "XX9ABC" -> "XX", "john smith" -> "JS". */
    static String initials(String name) {
        String[] parts = name.trim().split("[\\s\\-_.]+");
        if (parts.length >= 2 && !parts[0].isEmpty() && !parts[parts.length - 1].isEmpty()) {
            return (parts[0].substring(0, 1) + parts[parts.length - 1].substring(0, 1)).toUpperCase();
        }
        String p = parts.length > 0 ? parts[0] : name;
        return (p.length() >= 2 ? p.substring(0, 2) : p).toUpperCase();
    }
}
