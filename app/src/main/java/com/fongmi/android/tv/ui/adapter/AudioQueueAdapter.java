package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.databinding.ItemAudioQueueBinding;

import java.util.ArrayList;
import java.util.List;

public class AudioQueueAdapter extends RecyclerView.Adapter<AudioQueueAdapter.ViewHolder> {

    private List<Episode> items;
    private int selectedPosition;
    private OnClickListener listener;

    public AudioQueueAdapter() {
        items = new ArrayList<>();
        selectedPosition = 0;
    }

    public void addAll(List<Episode> items) {
        this.items = items == null ? new ArrayList<>() : items;
        if (selectedPosition >= this.items.size()) selectedPosition = 0;
        notifyDataSetChanged();
    }

    public void setSelectedPosition(int position) {
        this.selectedPosition = position;
        notifyDataSetChanged();
    }

    public void setOnItemClickListener(OnClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(ItemAudioQueueBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Episode item = items.get(position);
        holder.binding.name.setText(item.getName());
        holder.binding.name.setTextColor(holder.binding.name.getContext().getColor(position == selectedPosition ? R.color.white : R.color.white_70));
        holder.binding.getRoot().setOnClickListener(v -> {
            if (listener != null) listener.onItemClick(item);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public interface OnClickListener {
        void onItemClick(Episode item);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {

        private final ItemAudioQueueBinding binding;

        ViewHolder(ItemAudioQueueBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
