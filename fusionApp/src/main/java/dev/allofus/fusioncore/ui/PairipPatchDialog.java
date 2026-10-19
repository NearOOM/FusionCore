package dev.allofus.fusioncore.ui;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import java.io.File;

import dev.allofus.fusioncore.R;
import dev.allofus.fusioncore.repairip.bridge.PairipPatchRunner;

public class PairipPatchDialog extends DialogFragment
        implements PairipPatchRunner.Listener {

    private static final String ARG_PKG   = "pkg";
    private static final String ARG_LABEL = "label";

    private TextView statusView;
    private TextView logView;
    private ScrollView logScroll;

    public static PairipPatchDialog newInstance(String packageName, String label) {
        PairipPatchDialog d = new PairipPatchDialog();
        Bundle b = new Bundle();
        b.putString(ARG_PKG, packageName);
        b.putString(ARG_LABEL, label);
        d.setArguments(b);
        return d;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View v = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_pairip_patch, null, false);

        statusView = v.findViewById(R.id.patchStatus);
        logView    = v.findViewById(R.id.patchLog);
        logScroll  = v.findViewById(R.id.patchLogScroll);

        Bundle args = requireArguments();
        String pkg   = args.getString(ARG_PKG);
        String label = args.getString(ARG_LABEL);

        setCancelable(false);

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle("Pairip: " + label)
                .setView(v)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
            PairipPatchRunner.patch(requireContext().getApplicationContext(),
                    pkg, label, this);
        });
        dialog.setCanceledOnTouchOutside(false);
        return dialog;
    }

    @Override public void onStart(String label) { statusView.setText("Running..."); }

    @Override public void onLog(String line) {
        if (logView == null) return;
        logView.append(line);
        logView.append("\n");
        if (logScroll != null) logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    @Override public void onSuccess(File outputApk) {
        statusView.setText("Done: " + outputApk.getName());
        enableClose();
    }

    @Override public void onError(Throwable t) {
        statusView.setText("Failed: " + t.getMessage());
        onLog("!! " + t);
        enableClose();
    }

    private void enableClose() {
        Dialog d = getDialog();
        if (d instanceof AlertDialog) {
            ((AlertDialog) d).getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
            setCancelable(true);
            d.setCanceledOnTouchOutside(true);
        }
    }
}
