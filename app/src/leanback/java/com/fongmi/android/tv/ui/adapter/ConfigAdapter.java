package com.fongmi.android.tv.ui.adapter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.ConfigSummary;
import com.fongmi.android.tv.databinding.AdapterConfigBinding;

import java.text.DateFormat;
import java.util.List;

public class ConfigAdapter extends RecyclerView.Adapter<ConfigAdapter.ViewHolder> {

    private final OnClickListener listener;
    private List<Config> mItems;
    private boolean readOnly;

    public ConfigAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public interface OnClickListener {

        void onTextClick(Config item);

        void onDeleteClick(Config item);
    }

    public ConfigAdapter readOnly(boolean readOnly) {
        this.readOnly = readOnly;
        return this;
    }

    public ConfigAdapter addAll(int type) {
        mItems = Config.getAll(type);
        return this;
    }

    public int remove(Config item) {
        int position = mItems.indexOf(item);
        if (position == -1) return -1;
        item.delete();
        mItems.remove(position);
        notifyItemRemoved(position);
        return getItemCount();
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterConfigBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Config item = mItems.get(position);
        ConfigSummary summary = new ConfigSummary(item, position == 0);
        String title = summary.current() ? "[当前] " + summary.title() : summary.title();
        String time = item.getTime() > 0 ? DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(item.getTime()) : "";
        holder.binding.text.setText(title);
        holder.binding.subtitle.setText(TextUtils.isEmpty(time) ? summary.subtitle() : summary.subtitle() + "\n" + time);
        holder.binding.text.setOnClickListener(v -> listener.onTextClick(item));
        holder.binding.delete.setVisibility(readOnly ? View.GONE : View.VISIBLE);
        holder.binding.delete.setOnClickListener(v -> listener.onDeleteClick(item));
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterConfigBinding binding;

        public ViewHolder(@NonNull AdapterConfigBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
