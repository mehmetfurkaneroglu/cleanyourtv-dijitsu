package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.DayOfWeek;
import java.util.List;

/**
 * The Program pill's screen: the weekly programs (ProgramSchedule) as glass rows, and an editor made of the same
 * rows so the remote is all it takes. Days toggle with OK; hour, minute and channel move with Left/Right and take
 * the remote's digit keys.
 */
public final class ScheduleActivity extends Activity {

    private LinearLayout rows;
    private TextView title;
    private List<ProgramSchedule.Program> programs;
    /** The program in the editor, its index in {@link #programs} (-1 for a new one). */
    private ProgramSchedule.Program editing;
    private int editingIndex = -1;
    /** Digits typed into a number row: a second digit on the same row within this window extends the first one. */
    private long lastDigitAt;
    private View lastDigitRow;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_schedule);
        rows = findViewById(R.id.rows);
        title = findViewById(R.id.title);
        programs = ProgramSchedule.load(this);
        showList();
    }

    @Override
    public void onBackPressed() {
        if (editing != null) showList();
        else super.onBackPressed();
    }

    private void showList() {
        editing = null;
        title.setText(R.string.program);
        rows.removeAllViews();
        for (int i = 0; i < programs.size(); i++) {
            final int index = i;
            addRow(R.drawable.ic_schedule, programs.get(i).describe(this), null, v -> edit(index));
        }
        addRow(R.drawable.ic_add, getString(R.string.program_add), programs.isEmpty()
                ? getString(R.string.program_hint) : null, v -> edit(-1));
        rows.getChildAt(0).requestFocus();
    }

    private void edit(int index) {
        editingIndex = index;
        editing = new ProgramSchedule.Program();
        if (index >= 0) {
            ProgramSchedule.Program p = programs.get(index);
            editing.days = p.days;
            editing.hour = p.hour;
            editing.minute = p.minute;
            editing.channel = p.channel;
            editing.duration = p.duration;
        }
        title.setText(index >= 0 ? R.string.program_edit : R.string.program_add);
        rows.removeAllViews();
        for (DayOfWeek day : DayOfWeek.values()) {
            View row = addRow(R.drawable.ic_calendar, ProgramSchedule.dayName(day), null, null);
            int bit = 1 << (day.getValue() - 1);
            row.setOnClickListener(v -> {
                editing.days ^= bit;
                showDay(row, (editing.days & bit) != 0);
            });
            showDay(row, (editing.days & bit) != 0);
        }
        View hour = addRow(R.drawable.ic_schedule, "", getString(R.string.program_adjust_hint), null);
        View minute = addRow(R.drawable.ic_schedule, "", getString(R.string.program_adjust_hint), null);
        View channel = addRow(R.drawable.ic_livetv, "", getString(R.string.program_adjust_hint), null);
        View duration = addRow(R.drawable.ic_sleep, "", getString(R.string.program_adjust_hint), null);
        numberRow(hour, 0, 23, 1, () -> editing.hour, v -> editing.hour = v, R.string.program_hour);
        numberRow(minute, 0, 59, 1, () -> editing.minute, v -> editing.minute = v, R.string.program_minute);
        numberRow(channel, 1, 9999, 1, () -> editing.channel, v -> editing.channel = v, R.string.program_channel);
        numberRow(duration, 0, 300, 15, () -> editing.duration, v -> editing.duration = v, R.string.program_duration);
        addRow(R.drawable.ic_check, getString(R.string.program_save), null, v -> save());
        if (index >= 0) addRow(R.drawable.ic_delete, getString(R.string.program_delete), null, v -> delete());
        rows.getChildAt(0).requestFocus();
    }

    private void save() {
        if (editing.days == 0) {
            title.setText(R.string.program_pick_day);
            return;
        }
        if (editingIndex >= 0) programs.set(editingIndex, editing);
        else programs.add(editing);
        ProgramSchedule.save(this, programs);
        showList();
    }

    private void delete() {
        programs.remove(editingIndex);
        ProgramSchedule.save(this, programs);
        showList();
    }

    private interface Getter {
        int get();
    }

    private interface Setter {
        void set(int value);
    }

    /** A row whose value moves with Left/Right (wrapping) and takes digits typed on the remote. */
    private void numberRow(View row, int min, int max, int step, Getter get, Setter set, int label) {
        TextView text = row.findViewById(R.id.title);
        Runnable show = () -> text.setText(label == R.string.program_duration && get.get() == 0
                ? getString(R.string.program_duration_off) : getString(label, get.get()));
        show.run();
        row.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int value = get.get();
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                int span = max - min + 1;
                int delta = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? step : -step;
                set.set(min + Math.floorMod(value - min + delta, span));
            } else if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                int digit = keyCode - KeyEvent.KEYCODE_0;
                long now = event.getEventTime();
                int typed = row == lastDigitRow && now - lastDigitAt < 2_000 ? value * 10 + digit : digit;
                lastDigitAt = now;
                lastDigitRow = row;
                if (typed > max) typed = digit;
                set.set(Math.max(min, typed));
            } else {
                return false;
            }
            show.run();
            return true;
        });
    }

    private void showDay(View row, boolean on) {
        TextView status = row.findViewById(R.id.status);
        status.setVisibility(View.VISIBLE);
        status.setText(on ? R.string.program_day_on : R.string.program_day_off);
        row.findViewById(R.id.dot).setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private View addRow(int icon, CharSequence name, CharSequence hint, View.OnClickListener click) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(name);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        TextView status = row.findViewById(R.id.status);
        if (hint != null) status.setText(hint);
        else status.setVisibility(View.GONE);
        if (click != null) row.setOnClickListener(click);
        rows.addView(row);
        return row;
    }
}
