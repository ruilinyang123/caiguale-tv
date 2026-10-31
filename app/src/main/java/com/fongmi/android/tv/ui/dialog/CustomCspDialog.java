package com.fongmi.android.tv.ui.dialog;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.databinding.AdapterCustomCspBinding;
import com.fongmi.android.tv.databinding.DialogCustomCspBinding;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.CustomCspSetting;
import com.fongmi.android.tv.ui.custom.CustomTextListener;
import com.fongmi.android.tv.ui.custom.SettingClipboardOverlay;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CustomCspDialog extends BaseAlertDialog {

    private static final int MIN_INSERT_INDEX = 0;
    private static final int MAX_INSERT_INDEX = 9;
    private static final String KIND_WEB_HOME = "webHome";
    private static final String KIND_CSP = "csp";
    private static final String KIND_LIVE = "live";

    private DialogCustomCspBinding binding;
    private CustomCspSetting.Registry registry;
    private CspAdapter adapter;
    private ItemTouchHelper sortTouchHelper;
    private CustomCspSetting.Item pendingImport;
    private boolean pendingExtensionImport;
    private boolean pendingFilesImport;
    private TextInputEditText pendingFileTarget;
    private Set<String> initialItemIds = new HashSet<>();
    private final Set<String> pendingDeleteIds = new HashSet<>();
    private Runnable callback;
    private boolean enabled;
    private boolean textMode;
    private boolean sortMode;
    private boolean saved;
    private boolean recognizing;
    private long lastAddTime;

    public static void show(Fragment fragment, Runnable callback) {
        CustomCspDialog dialog = new CustomCspDialog();
        dialog.callback = callback;
        dialog.show(fragment.getChildFragmentManager(), null);
    }

    public static void show(FragmentActivity activity, Runnable callback) {
        CustomCspDialog dialog = new CustomCspDialog();
        dialog.callback = callback;
        dialog.show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogCustomCspBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog).setView(getBinding().getRoot());
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() == null) return;
        setCancelable(false);
        getDialog().setCanceledOnTouchOutside(false);
        Window window = getDialog().getWindow();
        if (window == null) return;
        WindowManager.LayoutParams params = window.getAttributes();
        int screenWidth = ResUtil.getScreenWidth(requireContext());
        int screenHeight = ResUtil.getScreenHeight(requireContext());
        boolean land = ResUtil.isLand(requireContext());
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.getDecorView().setPadding(0, 0, 0, 0);
        params.width = (int) (screenWidth * (land ? 0.76f : 0.94f));
        params.height = land ? (int) (screenHeight * 0.98f) : WindowManager.LayoutParams.WRAP_CONTENT;
        window.setAttributes(params);
        window.setLayout(params.width, params.height);
        ViewGroup.LayoutParams rootParams = binding.root.getLayoutParams();
        rootParams.height = land ? params.height : ViewGroup.LayoutParams.WRAP_CONTENT;
        binding.root.setLayoutParams(rootParams);
        LinearLayoutCompat.LayoutParams scrollParams = (LinearLayoutCompat.LayoutParams) binding.contentScroll.getLayoutParams();
        scrollParams.height = land ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT;
        scrollParams.weight = land ? 1 : 0;
        binding.contentScroll.setLayoutParams(scrollParams);
        binding.contentScroll.setMaxHeight(land ? 0 : (int) (screenHeight * 0.58f));
        binding.enabled.requestFocus();
    }

    @Override
    protected void initView() {
        registry = CustomCspSetting.load();
        initialItemIds = itemIds(registry.getItems());
        adapter = new CspAdapter(new ArrayList<>(registry.getItems()));
        enabled = registry.isEnabled();
        updateEnabledText();
        setInsertIndex(registry.getInsertIndex());
        binding.recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.recycler.setItemAnimator(null);
        binding.recycler.setAdapter(adapter);
        if (Util.isMobile()) attachSortTouchHelper();
        binding.modeGroup.check(R.id.uiMode);
        syncJsonFromForm(false);
        showTextMode(false);
    }

    @Override
    protected void initEvent() {
        binding.enabled.setOnClickListener(view -> {
            enabled = !enabled;
            updateEnabledText();
        });
        binding.insertMinus.setOnClickListener(view -> changeInsertIndex(-1));
        binding.insertPlus.setOnClickListener(view -> changeInsertIndex(1));
        binding.modeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.textMode && !showTextMode(true)) binding.modeGroup.check(R.id.uiMode);
            if (checkedId == R.id.uiMode && !showTextMode(false)) binding.modeGroup.check(R.id.textMode);
        });
        setupScrollableText(binding.jsonText);
        binding.add.setOnClickListener(view -> addItem());
        binding.recognize.setOnClickListener(view -> showRecognizeDialog());
        binding.sort.setOnClickListener(view -> setSortMode(!sortMode));
        binding.negative.setOnClickListener(view -> {
            if (sortMode) setSortMode(false);
            else closeAndSave(false);
        });
        binding.positive.setOnClickListener(view -> onPositive());
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        save(false);
        super.onCancel(dialog);
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        save(false);
        super.onDismiss(dialog);
    }

    private void updateEnabledText() {
        binding.enabled.setText(enabled ? R.string.setting_enable : R.string.setting_disable);
        binding.enabled.setAlpha(enabled ? 1.0f : 0.65f);
    }

    private void setupScrollableText(EditText input) {
        input.setSelectAllOnFocus(false);
        input.setHorizontallyScrolling(true);
        input.setHorizontalScrollBarEnabled(true);
        input.setVerticalScrollBarEnabled(true);
        input.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                view.post(() -> disallowParentIntercept(view, false));
            } else {
                disallowParentIntercept(view, true);
            }
            return false;
        });
    }

    private void disallowParentIntercept(View view, boolean disallow) {
        ViewParent parent = view.getParent();
        while (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallow);
            parent = parent.getParent();
        }
    }

    private void changeInsertIndex(int delta) {
        setInsertIndex(getInsertIndex() + delta);
    }

    private void setInsertIndex(int index) {
        int value = clampInsertIndex(index);
        binding.insertIndex.setText(String.valueOf(value + 1));
        binding.insertMinus.setAlpha(value > MIN_INSERT_INDEX ? 1.0f : 0.45f);
        binding.insertPlus.setAlpha(value < MAX_INSERT_INDEX ? 1.0f : 0.45f);
    }

    private boolean showTextMode(boolean text) {
        if (text == textMode) {
            updateModeVisibility();
            return true;
        }
        if (sortMode) setSortMode(false);
        if (text && !syncJsonFromForm(true)) return false;
        else if (!syncFormFromJson(true)) return false;
        textMode = text;
        updateModeVisibility();
        return true;
    }

    private void updateModeVisibility() {
        binding.recycler.setVisibility(textMode ? View.GONE : View.VISIBLE);
        binding.jsonLayout.setVisibility(textMode ? View.VISIBLE : View.GONE);
        binding.add.setVisibility(textMode || sortMode ? View.GONE : View.VISIBLE);
        binding.recognize.setVisibility(sortMode ? View.GONE : View.VISIBLE);
        binding.sort.setVisibility(Util.isMobile() && !textMode ? View.VISIBLE : View.GONE);
        binding.sort.setText(sortMode ? R.string.setting_custom_csp_sort_done : R.string.setting_custom_csp_sort);
    }

    private void setSortMode(boolean sort) {
        if (sort && (!Util.isMobile() || textMode)) return;
        if (sortMode == sort) {
            updateModeVisibility();
            return;
        }
        syncAllVisibleRows();
        sortMode = sort;
        adapter.setSortMode(sortMode);
        updateModeVisibility();
        if (sortMode) focusSortList();
        else binding.sort.requestFocus();
    }

    private void focusSortList() {
        binding.recycler.post(() -> {
            RecyclerView.ViewHolder holder = binding.recycler.findViewHolderForAdapterPosition(0);
            if (holder != null) holder.itemView.requestFocus();
            else binding.recycler.requestFocus();
        });
    }

    private void attachSortTouchHelper() {
        sortTouchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean isLongPressDragEnabled() {
                return false;
            }

            @Override
            public boolean isItemViewSwipeEnabled() {
                return false;
            }

            @Override
            public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                return sortMode ? super.getMovementFlags(recyclerView, viewHolder) : 0;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder source, @NonNull RecyclerView.ViewHolder target) {
                adapter.moveDisplay(source.getBindingAdapterPosition(), target.getBindingAdapterPosition());
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            }
        });
        sortTouchHelper.attachToRecyclerView(binding.recycler);
    }

    private void showSortActions(int position) {
        if (!sortMode || position == RecyclerView.NO_POSITION || position >= adapter.getItemCount()) return;
        String[] actions = {
                getString(R.string.setting_custom_csp_sort_top),
                getString(R.string.setting_custom_csp_sort_forward_five),
                getString(R.string.setting_custom_csp_sort_backward_five),
                getString(R.string.setting_custom_csp_sort_bottom),
                getString(R.string.setting_custom_csp_sort_move_to)
        };
        ChoiceDialog.showSingle(this, R.string.setting_custom_csp_sort_more, actions, -1, which -> {
            if (which == 0) moveSortItem(position, 0);
            else if (which == 1) moveSortItem(position, position - 5);
            else if (which == 2) moveSortItem(position, position + 5);
            else if (which == 3) moveSortItem(position, adapter.getItemCount() - 1);
            else showMoveToPosition(position);
        });
    }

    private void showMoveToPosition(int index) {
        if (!sortMode || index < 0 || index >= adapter.getItemCount()) return;
        TextInputEditText input = createInput(false);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(index + 1));
        input.selectAll();
        showManualCloseDialog(new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.setting_custom_csp_sort_move_to_title)
                .setView(createInputPanel(getString(R.string.setting_custom_csp_sort_move_to_hint, adapter.getItemCount()), input))
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> moveSortItem(index, parseInt(input.getText().toString(), index + 1) - 1))
                .setNegativeButton(R.string.dialog_negative, null));
    }

    private void moveSortItem(int fromIndex, int toIndex) {
        int position = adapter.moveItemToIndex(fromIndex, Math.max(0, Math.min(toIndex, adapter.getItemCount() - 1)));
        if (position >= 0) binding.recycler.scrollToPosition(position);
    }

    private void addItem() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastAddTime < 500) return;
        lastAddTime = now;
        CustomCspSetting.Item item = CustomCspSetting.createDefaultItem();
        item.setName(nextName(KIND_WEB_HOME));
        adapter.add(item);
        binding.recycler.scrollToPosition(adapter.getItemCount() - 1);
    }

    private String nextName(String kind) {
        String prefix = getKindPrefix(kind);
        int max = 0;
        for (CustomCspSetting.Item item : adapter.getItems()) {
            if (!item.getKind().equals(kind)) continue;
            String name = item.getName();
            if (name.equals(prefix)) max = Math.max(max, 1);
            else if (name.startsWith(prefix + " ")) max = Math.max(max, parseInt(name.substring(prefix.length() + 1), 0));
        }
        int next = Math.max(1, max + 1);
        return getString(KIND_WEB_HOME.equals(kind) ? R.string.setting_custom_csp_webhome_name : KIND_LIVE.equals(kind) ? R.string.setting_custom_csp_live_name : R.string.setting_custom_csp_common_name, next);
    }

    private String getKindPrefix(String kind) {
        return getString(KIND_WEB_HOME.equals(kind) ? R.string.setting_custom_csp_webhome : KIND_LIVE.equals(kind) ? R.string.setting_custom_csp_live : R.string.setting_custom_csp_common);
    }

    private boolean onPositive() {
        return closeAndSave(true);
    }

    private boolean closeAndSave(boolean validate) {
        if (!save(validate)) return false;
        focusBeforeDismiss();
        dismiss();
        return true;
    }

    private void focusBeforeDismiss() {
        if (binding == null) return;
        View focus = binding.root.findFocus();
        if (focus != null) focus.clearFocus();
        binding.positive.requestFocus();
    }

    private void focusBeforeRemove(View removed) {
        if (binding == null || removed == null) return;
        View focus = binding.root.findFocus();
        if (isDescendant(focus, removed)) {
            focus.clearFocus();
            binding.add.requestFocus();
        }
    }

    private boolean isDescendant(View child, View parent) {
        if (child == null || parent == null) return false;
        if (child == parent) return true;
        ViewParent viewParent = child.getParent();
        while (viewParent instanceof View) {
            if (viewParent == parent) return true;
            viewParent = viewParent.getParent();
        }
        return false;
    }

    private boolean save(boolean validate) {
        if (saved) return true;
        if (textMode && !syncFormFromJson(validate)) {
            if (validate) return false;
            saved = true;
            return true;
        }
        syncAllVisibleRows();
        if (validate && adapter.hasInvalidExtensions()) {
            Notify.show(R.string.setting_custom_csp_extensions_invalid);
            return false;
        }
        registry.setEnabled(enabled);
        registry.setInsertIndex(getInsertIndex());
        registry.setItems(new ArrayList<>(adapter.getItems()));
        CustomCspSetting.save(registry);
        cleanupDeletedFiles(registry);
        reloadConfigs();
        if (callback != null) callback.run();
        saved = true;
        return true;
    }

    private void reloadConfigs() {
        VodConfig.get().clear().config(VodConfig.get().getConfig()).load(new Callback() {
        });
        if (LiveConfig.hasLoadedLives() || !LiveConfig.get().getConfig().isEmpty() || CustomCspSetting.hasLives()) LiveConfig.get().clear().config(LiveConfig.get().getConfig()).load(new Callback() {
        });
    }

    private boolean syncJsonFromForm(boolean validate) {
        syncAllVisibleRows();
        if (validate && adapter.hasInvalidExtensions()) {
            Notify.show(R.string.setting_custom_csp_extensions_invalid);
            return false;
        }
        registry.setEnabled(enabled);
        registry.setInsertIndex(getInsertIndex());
        registry.setItems(new ArrayList<>(adapter.getItems()));
        binding.jsonText.setText(new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(registry.normalize()));
        return true;
    }

    private boolean syncFormFromJson(boolean validate) {
        String text = binding.jsonText.getText() == null ? "" : binding.jsonText.getText().toString().trim();
        try {
            registry = TextUtils.isEmpty(text) ? new CustomCspSetting.Registry() : CustomCspSetting.parse(text);
        } catch (Exception e) {
            if (validate) Notify.show(R.string.setting_custom_csp_json_invalid);
            return false;
        }
        adapter.setItems(new ArrayList<>(registry.getItems()));
        enabled = registry.isEnabled();
        updateEnabledText();
        setInsertIndex(registry.getInsertIndex());
        return true;
    }

    private void showRecognizeDialog() {
        if (sortMode) setSortMode(false);
        syncAllVisibleRows();
        TextInputEditText input = createInput(true);
        input.setMinLines(10);
        input.setMaxLines(16);
        setupScrollableText(input);
        showManualCloseDialog(new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.setting_custom_csp_recognize_title)
                .setView(createInputPanel(R.string.setting_custom_csp_recognize_hint, input))
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> recognize(input.getText().toString()))
                .setNegativeButton(R.string.dialog_negative, null));
    }

    private void recognize(String text) {
        if (recognizing) return;
        if (TextUtils.isEmpty(text) || TextUtils.isEmpty(text.trim())) {
            Notify.show(R.string.setting_custom_csp_recognize_empty);
            return;
        }
        recognizing = true;
        Task.execute(() -> {
            Exception[] failure = new Exception[1];
            List<CustomCspSetting.Item> items = Collections.emptyList();
            try {
                items = recognizedItems(text);
            } catch (Exception e) {
                failure[0] = e;
            }
            List<CustomCspSetting.Item> result = items;
            Exception error = failure[0];
            App.post(() -> finishRecognize(result, error));
        });
    }

    private void finishRecognize(List<CustomCspSetting.Item> items, Exception error) {
        if (!isAdded() || binding == null) return;
        recognizing = false;
        if (error != null || items.isEmpty()) {
            Notify.show(R.string.setting_custom_csp_recognize_invalid);
            return;
        }
        if (textMode && !syncFormFromJson(true)) return;
        else if (!textMode) syncAllVisibleRows();
        List<CustomCspSetting.Item> next = new ArrayList<>(adapter.getItems());
        next.addAll(items);
        adapter.setItems(next);
        if (textMode) syncJsonFromForm(false);
        Notify.show(getString(R.string.setting_custom_csp_recognize_done, items.size()));
    }

    private List<CustomCspSetting.Item> recognizedItems(String text) throws Exception {
        String value = stripRecognizeText(text);
        List<String> candidates = new ArrayList<>();
        addRecognizeCandidate(candidates, value);
        String stripped = stripTrailingSeparators(value);
        addRecognizeCandidate(candidates, stripped);
        String closed = closeUnbalancedJson(stripped);
        addRecognizeCandidate(candidates, closed);
        if (!closed.startsWith("[")) addRecognizeCandidate(candidates, "[" + closed + "]");
        Exception failure = null;
        for (String candidate : candidates) {
            try {
                CustomCspSetting.Registry parsed = CustomCspSetting.parse(candidate);
                List<CustomCspSetting.Item> items = new ArrayList<>(parsed.getItems());
                if (allowsRemoteLiveRecognition(candidate)) recognizeRemoteLive(items);
                items.removeIf(item -> item == null || !item.isValid());
                if (!items.isEmpty()) return items;
            } catch (Exception e) {
                failure = e;
            }
        }
        if (failure != null) throw failure;
        return Collections.emptyList();
    }

    private boolean allowsRemoteLiveRecognition(String candidate) {
        try {
            JsonElement element = CustomCspSetting.parseFlexible(candidate);
            if (hasExplicitKind(element)) return false;
            if (!element.isJsonObject()) return true;
            JsonObject object = element.getAsJsonObject();
            if (object.has("sites") || object.has("items")) return false;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasExplicitKind(JsonElement element) {
        if (element == null || element.isJsonNull()) return false;
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("kind") && object.get("kind").isJsonPrimitive() && !TextUtils.isEmpty(object.get("kind").getAsString())) return true;
            if (object.has("items") && hasExplicitKind(object.get("items"))) return true;
            return false;
        }
        if (!element.isJsonArray()) return false;
        for (JsonElement child : element.getAsJsonArray()) if (hasExplicitKind(child)) return true;
        return false;
    }

    private void recognizeRemoteLive(List<CustomCspSetting.Item> items) {
        for (CustomCspSetting.Item item : items) {
            if (item == null || item.isLive() || item.isWebHome()) continue;
            String api = item.getApi();
            if (!CustomCspSetting.isRemoteScript(api)) continue;
            String script = OkHttp.string(api, 8000);
            if (!CustomCspSetting.hasLiveMethod(api, script)) continue;
            String ext = item.getExt();
            String jar = item.getJar();
            item.setKind(KIND_LIVE);
            item.setApi(api);
            item.setExt(ext);
            item.setJar(jar);
        }
    }

    private void addRecognizeCandidate(List<String> candidates, String value) {
        if (TextUtils.isEmpty(value) || candidates.contains(value)) return;
        candidates.add(value);
    }

    private String stripRecognizeText(String text) {
        String value = text == null ? "" : text.trim();
        value = value.replaceAll("(?m)^```[a-zA-Z0-9_-]*\\s*$", "");
        value = value.replaceAll("(?m)^```\\s*$", "");
        return value.trim();
    }

    private String stripTrailingSeparators(String text) {
        String value = text == null ? "" : text.trim();
        while (value.endsWith(",") || value.endsWith(";") || value.endsWith("；")) value = value.substring(0, value.length() - 1).trim();
        return value;
    }

    private String closeUnbalancedJson(String text) {
        String value = text == null ? "" : text.trim();
        if (TextUtils.isEmpty(value)) return value;
        List<Character> stack = new ArrayList<>();
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                stack.add('}');
            } else if (c == '[') {
                stack.add(']');
            } else if (c == '}' || c == ']') {
                if (stack.isEmpty() || stack.remove(stack.size() - 1) != c) return value;
            }
        }
        if (inString || stack.isEmpty()) return value;
        StringBuilder builder = new StringBuilder(value);
        for (int i = stack.size() - 1; i >= 0; i--) builder.append(stack.get(i));
        return builder.toString();
    }

    private int getInsertIndex() {
        try {
            return clampInsertIndex(Integer.parseInt(binding.insertIndex.getText().toString().trim()) - 1);
        } catch (Exception e) {
            return MIN_INSERT_INDEX;
        }
    }

    private int clampInsertIndex(int index) {
        return Math.max(MIN_INSERT_INDEX, Math.min(MAX_INSERT_INDEX, index));
    }

    private void syncAllVisibleRows() {
        for (int i = 0; i < binding.recycler.getChildCount(); i++) {
            RecyclerView.ViewHolder holder = binding.recycler.getChildViewHolder(binding.recycler.getChildAt(i));
            if (holder instanceof CspAdapter.ViewHolder viewHolder) viewHolder.sync();
        }
    }

    private static void setText(EditText view, String text) {
        if (!TextUtils.equals(view.getText(), text)) view.setText(text);
    }

    private void chooseFile(CustomCspSetting.Item item) {
        syncAllVisibleRows();
        pendingImport = item;
        clearPendingFlags();
        FileChooser.from(launcher).show("text/html", new String[]{"text/html", "text/*", "application/octet-stream"});
    }

    private void clearPendingFlags() {
        pendingExtensionImport = false;
        pendingFilesImport = false;
        pendingFileTarget = null;
    }

    private void chooseExtensionFile(CustomCspSetting.Item item) {
        syncAllVisibleRows();
        pendingImport = item;
        clearPendingFlags();
        pendingExtensionImport = true;
        FileChooser.from(launcher).show("text/*", new String[]{"text/javascript", "application/javascript", "application/json", "text/css", "text/*", "application/octet-stream"});
    }

    private void chooseLocalFiles(CustomCspSetting.Item item) {
        syncAllVisibleRows();
        pendingImport = item;
        clearPendingFlags();
        pendingFilesImport = true;
        View focus = binding.root.findFocus();
        pendingFileTarget = focus instanceof TextInputEditText input && input.isEnabled() ? input : null;
        FileChooser.from(launcher).show("*/*", new String[]{"text/javascript", "application/javascript", "application/json", "application/java-archive", "application/octet-stream", "text/*", "*/*"}, true);
    }

    private void importExtensionFile(CustomCspSetting.Item item, String path) throws Exception {
        String content = Path.read(Path.local(path));
        if (TextUtils.isEmpty(content)) throw new IllegalArgumentException(getString(R.string.web_home_extension_source_empty));
        String text = extensionArrayText(item, path, content);
        item.setExtensionsExpanded(true);
        item.setExtensionsText(text);
    }

    private String extensionArrayText(CustomCspSetting.Item item, String path, String content) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        String lower = name.toLowerCase();
        JsonArray array = new JsonArray();
        if (lower.endsWith(".json")) {
            JsonElement element = JsonParser.parseString(content.trim());
            if (element.isJsonObject() && element.getAsJsonObject().has("extensions")) element = element.getAsJsonObject().get("extensions");
            if (element.isJsonArray()) return pretty(element);
            array.add(element);
            return pretty(array);
        }
        JsonObject object = new JsonObject();
        object.addProperty("id", extensionId(item, name));
        object.addProperty("name", name);
        object.addProperty("runAt", "document-end");
        object.addProperty("sourceType", "file");
        object.addProperty("code", lower.endsWith(".css") ? "GM_addStyle(" + App.gson().toJson(content) + ");" : content);
        array.add(object);
        return pretty(array);
    }

    private String extensionId(CustomCspSetting.Item item, String name) {
        String base = (item == null ? "" : item.getKey()) + "-" + name;
        String value = base.toLowerCase().replaceAll("[^a-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
        return TextUtils.isEmpty(value) ? "local-extension" : value;
    }

    private void importLocalFiles(CustomCspSetting.Item item, List<String> paths) {
        List<String> urls = new ArrayList<>();
        for (String path : paths) {
            String url = CustomCspSetting.copyFile(Path.local(path), item.getId());
            urls.add(url);
            SettingClipboardOverlay.record(url);
        }
        if (!urls.isEmpty() && pendingFileTarget != null) {
            setText(pendingFileTarget, urls.get(0));
            pendingFileTarget.setSelection(pendingFileTarget.length());
            pendingFileTarget.requestFocus();
        }
        Notify.show(R.string.copied);
    }

    private void cleanupDeletedFiles(CustomCspSetting.Registry current) {
        Set<String> currentIds = itemIds(current.getItems());
        Set<String> deleted = new HashSet<>(pendingDeleteIds);
        for (String id : initialItemIds) if (!currentIds.contains(id)) deleted.add(id);
        for (String id : deleted) CustomCspSetting.deleteFiles(id);
        initialItemIds = currentIds;
        pendingDeleteIds.clear();
    }

    private Set<String> itemIds(List<CustomCspSetting.Item> items) {
        Set<String> ids = new HashSet<>();
        if (items == null) return ids;
        for (CustomCspSetting.Item item : items) if (item != null && !TextUtils.isEmpty(item.getId())) ids.add(item.getId());
        return ids;
    }

    private String pretty(JsonElement element) {
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(element);
    }

    private void editCode(CustomCspSetting.Item item) {
        syncAllVisibleRows();
        TextInputEditText input = createInput(true);
        input.setMinLines(8);
        input.setMaxLines(14);
        input.setText(Path.read(CustomCspSetting.file(item.getId(), "index.html")));
        setupScrollableText(input);
        showManualCloseDialog(new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.setting_custom_csp_code)
                .setView(createInputPanel(R.string.setting_custom_csp_code, input))
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> saveCode(item, input.getText().toString()))
                .setNegativeButton(R.string.dialog_negative, null));
    }

    private void editLink(CustomCspSetting.Item item) {
        syncAllVisibleRows();
        TextInputEditText input = createInput(false);
        input.setText(item.getHomePage());
        showManualCloseDialog(new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setTitle(R.string.setting_custom_csp_link)
                .setView(createInputPanel(R.string.setting_custom_csp_link, input))
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    item.setHomePage(input.getText().toString().trim());
                    adapter.notifyDataSetChanged();
                })
                .setNegativeButton(R.string.dialog_negative, null));
    }

    private void showManualCloseDialog(MaterialAlertDialogBuilder builder) {
        AlertDialog dialog = builder.create();
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    private TextInputEditText createInput(boolean multiline) {
        TextInputEditText input = new TextInputEditText(requireContext());
        input.setSelectAllOnFocus(false);
        input.setSingleLine(!multiline);
        input.setTextColor(Color.BLACK);
        input.setHintTextColor(Color.parseColor("#666666"));
        input.setInputType(multiline ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setGravity(multiline ? Gravity.START | Gravity.TOP : Gravity.CENTER_VERTICAL);
        return input;
    }

    private View createInputPanel(int hint, TextInputEditText input) {
        return createInputPanel(getString(hint), input);
    }

    private View createInputPanel(String hint, TextInputEditText input) {
        LinearLayoutCompat container = new LinearLayoutCompat(requireContext());
        container.setOrientation(LinearLayoutCompat.VERTICAL);
        container.setPadding(ResUtil.dp2px(20), ResUtil.dp2px(8), ResUtil.dp2px(20), 0);
        TextInputLayout layout = new TextInputLayout(requireContext());
        layout.setHint(hint);
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        layout.setBoxBackgroundColor(Color.WHITE);
        layout.setBoxStrokeColor(ResUtil.getColor(R.color.dialog_outlined_button_stroke));
        layout.setHintTextColor(ColorStateList.valueOf(Color.parseColor("#5F6368")));
        layout.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(layout, new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return container;
    }

    private void saveCode(CustomCspSetting.Item item, String code) {
        Path.write(CustomCspSetting.file(item.getId(), "index.html"), code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        item.setHomePage(CustomCspSetting.localUrl(item.getId(), "index.html"));
        adapter.notifyDataSetChanged();
    }

    private final ActivityResultLauncher<Intent> launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || pendingImport == null) return;
        List<String> paths = FileChooser.getPathsFromIntent(result.getData());
        if (paths.isEmpty()) return;
        String path = paths.get(0);
        try {
            if (pendingExtensionImport) {
                importExtensionFile(pendingImport, path);
                clearPendingImport();
                adapter.notifyDataSetChanged();
                return;
            }
            if (pendingFilesImport) {
                importLocalFiles(pendingImport, paths);
                clearPendingImport();
                adapter.notifyDataSetChanged();
                return;
            }
            Path.copy(Path.local(path), CustomCspSetting.file(pendingImport.getId(), "index.html"));
            pendingImport.setHomePage(CustomCspSetting.localUrl(pendingImport.getId(), "index.html"));
            clearPendingImport();
            adapter.notifyDataSetChanged();
        } catch (Exception e) {
            clearPendingImport();
            Notify.show(e.getMessage());
        }
    });

    private void clearPendingImport() {
        pendingImport = null;
        clearPendingFlags();
    }

    private class CspAdapter extends RecyclerView.Adapter<CspAdapter.ViewHolder> {

        private final List<CustomCspSetting.Item> items;
        private boolean sortMode;

        CspAdapter(List<CustomCspSetting.Item> items) {
            this.items = items;
        }

        List<CustomCspSetting.Item> getItems() {
            return items;
        }

        void setSortMode(boolean sortMode) {
            if (this.sortMode == sortMode) return;
            this.sortMode = sortMode;
            notifyDataSetChanged();
        }

        void add(CustomCspSetting.Item item) {
            items.add(item);
            notifyItemInserted(items.size() - 1);
        }

        void setItems(List<CustomCspSetting.Item> items) {
            this.items.clear();
            this.items.addAll(items);
            notifyDataSetChanged();
        }

        boolean hasInvalidExtensions() {
            for (CustomCspSetting.Item item : items) if (item.hasInvalidExtensions()) return true;
            return false;
        }

        void move(int from, int to) {
            moveDisplay(from, to);
        }

        int moveDisplay(int fromPosition, int toPosition) {
            if (fromPosition < 0 || toPosition < 0 || fromPosition >= items.size() || toPosition >= items.size()) return -1;
            return moveItemToIndex(fromPosition, toPosition);
        }

        int moveItemToIndex(int fromIndex, int toIndex) {
            if (fromIndex < 0 || toIndex < 0 || fromIndex >= items.size() || toIndex >= items.size()) return -1;
            if (fromIndex == toIndex) return toIndex;
            syncAllVisibleRows();
            CustomCspSetting.Item item = items.remove(fromIndex);
            items.add(toIndex, item);
            notifyItemMoved(fromIndex, toIndex);
            notifyItemRangeChanged(Math.min(fromIndex, toIndex), Math.abs(fromIndex - toIndex) + 1);
            return toIndex;
        }

        void remove(int position, View removed) {
            if (position < 0 || position >= items.size()) return;
            syncAllVisibleRows();
            focusBeforeRemove(removed);
            CustomCspSetting.Item item = items.remove(position);
            if (!item.isLive() && item.site().getKey().equals(registry.getHomeKey())) registry.setHomeKey("");
            if (!TextUtils.isEmpty(item.getId())) pendingDeleteIds.add(item.getId());
            notifyItemRemoved(position);
        }

        void setHome(CustomCspSetting.Item item) {
            if (item.isLive()) return;
            syncAllVisibleRows();
            String key = item.site().getKey();
            registry.setHomeKey(key.equals(registry.getHomeKey()) ? "" : key);
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(AdapterCustomCspBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            holder.bind(items.get(position));
        }

        private class ViewHolder extends RecyclerView.ViewHolder {

            private final AdapterCustomCspBinding binding;
            private CustomCspSetting.Item item;
            private boolean bindingItem;
            private boolean autoName;
            private boolean autoKey;

            ViewHolder(@NonNull AdapterCustomCspBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
                binding.name.addTextChangedListener(new TextSync(this));
                binding.key.addTextChangedListener(new TextSync(this));
                binding.type.addTextChangedListener(new TextSync(this));
                binding.api.addTextChangedListener(new TextSync(this));
                binding.homePage.addTextChangedListener(new TextSync(this));
                binding.extensions.addTextChangedListener(new TextSync(this));
                binding.ext.addTextChangedListener(new TextSync(this));
                binding.jar.addTextChangedListener(new TextSync(this));
                binding.click.addTextChangedListener(new TextSync(this));
                binding.playUrl.addTextChangedListener(new TextSync(this));
                binding.liveUrl.addTextChangedListener(new TextSync(this));
                binding.logo.addTextChangedListener(new TextSync(this));
                binding.epg.addTextChangedListener(new TextSync(this));
                binding.ua.addTextChangedListener(new TextSync(this));
                binding.referer.addTextChangedListener(new TextSync(this));
                binding.origin.addTextChangedListener(new TextSync(this));
                binding.timeZone.addTextChangedListener(new TextSync(this));
                binding.timeout.addTextChangedListener(new TextSync(this));
                binding.enabled.setOnClickListener(view -> toggleEnabled());
                binding.home.setOnCheckedChangeListener((button, checked) -> onHomeChecked(checked));
                binding.typeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> onTypeChecked(checkedId, isChecked));
                binding.liveTypeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> onLiveTypeChecked(checkedId, isChecked));
                binding.playerTypeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> onPlayerTypeChecked(checkedId, isChecked));
                binding.hide.setOnCheckedChangeListener((button, checked) -> sync());
                binding.searchable.setOnCheckedChangeListener((button, checked) -> sync());
                binding.changeable.setOnCheckedChangeListener((button, checked) -> sync());
                binding.quickSearch.setOnCheckedChangeListener((button, checked) -> sync());
                binding.importFile.setOnClickListener(view -> chooseFile(item));
                binding.extensionsFile.setOnClickListener(view -> chooseExtensionFile(item));
                binding.localFiles.setOnClickListener(view -> chooseLocalFiles(item));
                binding.code.setOnClickListener(view -> editCode(item));
                binding.link.setOnClickListener(view -> editLink(item));
                binding.extensionsToggle.setOnClickListener(view -> toggleExtensions());
                binding.drag.setOnTouchListener((view, event) -> {
                    if (event.getActionMasked() != MotionEvent.ACTION_DOWN || sortTouchHelper == null || getBindingAdapterPosition() == RecyclerView.NO_POSITION) return false;
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    sortTouchHelper.startDrag(this);
                    return true;
                });
                binding.more.setOnClickListener(view -> showSortActions(getBindingAdapterPosition()));
                binding.up.setOnClickListener(view -> move(getBindingAdapterPosition(), getBindingAdapterPosition() - 1));
                binding.down.setOnClickListener(view -> move(getBindingAdapterPosition(), getBindingAdapterPosition() + 1));
                binding.delete.setOnClickListener(view -> remove(getBindingAdapterPosition(), itemView));
                setupScrollableText(binding.extensions);
            }

            void bind(CustomCspSetting.Item item) {
                this.item = item;
                bindingItem = true;
                autoName = isAutoName(item.getName(), item.getKind());
                autoKey = isAutoKey(item.getKey());
                binding.enabled.setAlpha(item.isEnabled() ? 1.0f : 0.65f);
                binding.enabled.setText(item.isEnabled() ? R.string.setting_enable : R.string.setting_disable);
                binding.typeGroup.check(item.isLive() ? R.id.liveMode : item.isWebHome() ? R.id.webHomeMode : R.id.cspMode);
                setText(binding.name, item.getName());
                setText(binding.key, item.getKey());
                setText(binding.type, String.valueOf(item.getType()));
                setText(binding.api, item.getApi());
                setText(binding.homePage, item.getHomePage());
                setText(binding.extensions, item.getExtensionsText());
                setText(binding.ext, item.getExt());
                setText(binding.jar, item.getJar());
                setText(binding.click, item.getClick());
                setText(binding.playUrl, item.getPlayUrl());
                setText(binding.liveUrl, item.getUrl());
                setText(binding.logo, item.getLogo());
                setText(binding.epg, item.getEpg());
                setText(binding.ua, item.getUa());
                setText(binding.referer, item.getReferer());
                setText(binding.origin, item.getOrigin());
                setText(binding.timeZone, item.getTimeZone());
                setText(binding.timeout, item.getTimeout() == null ? "" : String.valueOf(item.getTimeout()));
                binding.liveTypeGroup.check(liveTypeId(item.getType()));
                binding.playerTypeGroup.check(playerTypeId(item.getPlayerType()));
                binding.hide.setChecked(item.getHide() == 1);
                binding.searchable.setChecked(item.getSearchable() == 1);
                binding.changeable.setChecked(item.getChangeable() == 1);
                binding.quickSearch.setChecked(item.getQuickSearch() == 1);
                boolean home = !item.isLive() && item.site().getKey().equals(registry.getHomeKey());
                binding.home.setChecked(home);
                binding.drag.setVisibility(sortMode ? View.VISIBLE : View.GONE);
                binding.more.setVisibility(sortMode ? View.VISIBLE : View.GONE);
                binding.up.setVisibility(sortMode ? View.GONE : View.VISIBLE);
                binding.down.setVisibility(sortMode ? View.GONE : View.VISIBLE);
                updateTypePanels();
                updateExtensionsToggle();
                updateExtensionsError();
                updateValidity();
                bindingItem = false;
            }

            private void toggleEnabled() {
                if (item == null) return;
                boolean checked = !item.isEnabled();
                item.setEnabled(checked);
                binding.enabled.setAlpha(checked ? 1.0f : 0.65f);
                binding.enabled.setText(checked ? R.string.setting_enable : R.string.setting_disable);
            }

            private void toggleExtensions() {
                if (bindingItem || item == null) return;
                item.setExtensionsExpanded(!item.isExtensionsExpanded());
                if (!item.isExtensionsExpanded()) setText(binding.extensions, "");
                updateTypePanels();
                updateExtensionsToggle();
                updateExtensionsError();
                sync();
            }

            private void onHomeChecked(boolean checked) {
                if (bindingItem || item == null) return;
                if (item.isLive()) return;
                if (checked != item.site().getKey().equals(registry.getHomeKey())) setHome(item);
            }

            private void onTypeChecked(int checkedId, boolean isChecked) {
                if (bindingItem || item == null || !isChecked) return;
                String oldKind = item.getKind();
                String newKind = checkedId == R.id.liveMode ? KIND_LIVE : checkedId == R.id.webHomeMode ? KIND_WEB_HOME : KIND_CSP;
                if (oldKind.equals(newKind)) return;
                String oldHomeKey = item.isLive() ? "" : item.site().getKey();
                item.setKind(newKind);
                if (item.isLive() && registry.getHomeKey().equals(oldHomeKey)) registry.setHomeKey("");
                if (KIND_LIVE.equals(newKind) && !KIND_LIVE.equals(oldKind)) {
                    item.setApi("");
                    item.setExt("");
                    item.setJar("");
                    item.setClick("");
                    setText(binding.api, "");
                    setText(binding.ext, "");
                    setText(binding.jar, "");
                    setText(binding.click, "");
                }
                if (autoName) {
                    String name = nextName(newKind);
                    item.setName(name);
                    setText(binding.name, name);
                }
                updateTypePanels();
                updateValidity();
            }

            private void onLiveTypeChecked(int checkedId, boolean isChecked) {
                if (bindingItem || item == null || !item.isLive() || !isChecked) return;
                item.setType(liveTypeFromId(checkedId));
                updateValidity();
            }

            private void onPlayerTypeChecked(int checkedId, boolean isChecked) {
                if (bindingItem || item == null || !item.isLive() || !isChecked) return;
                item.setPlayerType(playerTypeFromId(checkedId));
                updateValidity();
            }

            private void updateTypePanels() {
                boolean webHome = item != null && item.isWebHome();
                boolean live = item != null && item.isLive();
                binding.webHomePanel.setVisibility(webHome ? View.VISIBLE : View.GONE);
                binding.home.setVisibility(live ? View.GONE : View.VISIBLE);
                binding.localFiles.setVisibility(!webHome && !live ? View.VISIBLE : View.GONE);
                binding.apiLayout.setVisibility(webHome || live ? View.GONE : View.VISIBLE);
                binding.homePageLayout.setVisibility(webHome ? View.VISIBLE : View.GONE);
                binding.extensionsPanel.setVisibility(webHome ? View.VISIBLE : View.GONE);
                binding.extensionsFile.setVisibility(webHome && item.isExtensionsExpanded() ? View.VISIBLE : View.GONE);
                binding.extensionsLayout.setVisibility(webHome && item.isExtensionsExpanded() ? View.VISIBLE : View.GONE);
                binding.liveUrlLayout.setVisibility(live ? View.VISIBLE : View.GONE);
                binding.liveTypePanel.setVisibility(View.GONE);
                binding.cspOptionsPanel.setVisibility(!live ? View.VISIBLE : View.GONE);
                binding.keyLayout.setVisibility(!live ? View.VISIBLE : View.GONE);
                binding.typeLayout.setVisibility(!webHome && !live ? View.VISIBLE : View.GONE);
                binding.liveMetaPanel.setVisibility(live ? View.VISIBLE : View.GONE);
                binding.liveHeaderPanel.setVisibility(live ? View.VISIBLE : View.GONE);
                binding.liveTunePanel.setVisibility(live ? View.VISIBLE : View.GONE);
                binding.flagsPanel.setVisibility(!webHome && !live ? View.VISIBLE : View.GONE);
                binding.advancedPanel.setVisibility(!webHome && !live ? View.VISIBLE : View.GONE);
                binding.playPanel.setVisibility(!webHome && !live ? View.VISIBLE : View.GONE);
                binding.playUrlLayout.setVisibility(live ? View.GONE : View.VISIBLE);
            }

            void sync() {
                if (item == null || bindingItem) return;
                String name = binding.name.getText().toString().trim();
                String key = binding.key.getText().toString().trim();
                if (!key.equals(item.getKey())) autoKey = false;
                autoName = autoName || isAutoName(item.getName(), item.getKind());
                if (!name.equals(item.getName())) autoName = false;
                item.setName(name);
                if (autoKey && !item.isLive() && !binding.key.getText().toString().trim().equals(item.getKey())) {
                    bindingItem = true;
                    setText(binding.key, item.getKey());
                    bindingItem = false;
                }
                if (item.isLive()) {
                    item.setUrl(binding.liveUrl.getText().toString().trim());
                    item.setExtensionsExpanded(false);
                    item.setApi(binding.api.getText().toString().trim());
                    item.setExt(binding.ext.getText().toString().trim());
                    item.setJar(binding.jar.getText().toString().trim());
                    item.setClick(binding.click.getText().toString().trim());
                    item.setLogo(binding.logo.getText().toString().trim());
                    item.setEpg(binding.epg.getText().toString().trim());
                    item.setUa(binding.ua.getText().toString().trim());
                    item.setReferer(binding.referer.getText().toString().trim());
                    item.setOrigin(binding.origin.getText().toString().trim());
                    item.setTimeZone(binding.timeZone.getText().toString().trim());
                    item.setTimeout(parseOptionalInt(binding.timeout.getText().toString()));
                    item.setHomePage("");
                    item.setPlayUrl("");
                } else if (!item.isWebHome()) {
                    item.setKey(binding.key.getText().toString().trim());
                    item.setExtensionsExpanded(false);
                    item.setType(parseInt(binding.type.getText().toString(), 3));
                    item.setApi(binding.api.getText().toString().trim());
                    item.setHide(binding.hide.isChecked() ? 1 : 0);
                    item.setSearchable(binding.searchable.isChecked() ? 1 : 0);
                    item.setChangeable(binding.changeable.isChecked() ? 1 : 0);
                    item.setQuickSearch(binding.quickSearch.isChecked() ? 1 : 0);
                }
                if (item.isLive()) {
                    item.setHomePage("");
                    item.setPlayUrl("");
                } else if (!item.isWebHome()) {
                    item.setHomePage("");
                    item.setExt(binding.ext.getText().toString().trim());
                    item.setJar(binding.jar.getText().toString().trim());
                    item.setClick(binding.click.getText().toString().trim());
                    item.setPlayUrl(binding.playUrl.getText().toString().trim());
                } else {
                    item.setKey(binding.key.getText().toString().trim());
                    item.setHomePage(binding.homePage.getText().toString().trim());
                    item.setExtensionsText(item.isExtensionsExpanded() ? binding.extensions.getText().toString() : "");
                    item.setClick("");
                    item.setPlayUrl("");
                }
                updateExtensionsToggle();
                updateExtensionsError();
                updateValidity();
            }

            private int liveTypeId(int value) {
                if (value == 1) return R.id.liveType1;
                if (value == 2) return R.id.liveType2;
                return R.id.liveType0;
            }

            private int playerTypeId(Integer value) {
                if (value == null) return R.id.playerTypeUnset;
                if (value == 0) return R.id.playerType0;
                if (value == 1) return R.id.playerType1;
                return R.id.playerType2;
            }

            private int liveTypeFromId(int id) {
                if (id == R.id.liveType1) return 1;
                if (id == R.id.liveType2) return 2;
                return 0;
            }

            private Integer playerTypeFromId(int id) {
                if (id == R.id.playerTypeUnset) return null;
                if (id == R.id.playerType0) return 0;
                if (id == R.id.playerType1) return 1;
                return 2;
            }

            private boolean isAutoName(String name, String kind) {
                String prefix = getKindPrefix(kind);
                if (TextUtils.isEmpty(name)) return true;
                if (name.equals(prefix)) return true;
                return name.matches(java.util.regex.Pattern.quote(prefix) + " \\d+");
            }

            private boolean isAutoKey(String key) {
                return TextUtils.isEmpty(key) || key.startsWith("__custom_csp_");
            }

            private void updateValidity() {
                if (item == null) return;
                boolean invalid = item.isEnabled() && !item.isValid();
                binding.getRoot().setActivated(invalid);
            }

            private void updateExtensionsError() {
                binding.extensionsLayout.setError(item != null && item.hasInvalidExtensions() ? getString(R.string.setting_custom_csp_extensions_invalid) : null);
            }

            private void updateExtensionsToggle() {
                boolean expanded = item != null && item.isWebHome() && item.isExtensionsExpanded();
                binding.extensionsToggle.setSelected(expanded);
                binding.extensionsToggle.setAlpha(expanded ? 1.0f : 0.65f);
            }
        }
    }

    private int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private Integer parseOptionalInt(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) return null;
        return parseInt(value, 0);
    }

    private static class TextSync extends CustomTextListener {

        private final CspAdapter.ViewHolder holder;

        TextSync(CspAdapter.ViewHolder holder) {
            this.holder = holder;
        }

        @Override
        public void afterTextChanged(Editable editable) {
            holder.sync();
        }
    }
}
