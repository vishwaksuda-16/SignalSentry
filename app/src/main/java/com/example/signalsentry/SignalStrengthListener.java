package com.example.signalsentry;

import android.os.Build;
import android.telephony.CellSignalStrength;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import java.util.List;

/**
 * Listener to capture cellular signal strength changes.
 */
@SuppressWarnings("deprecation")
public class SignalStrengthListener extends PhoneStateListener {
    
    public interface SignalCallback {
        void onSignalUpdate(int dbm);
    }

    private final SignalCallback callback;

    public SignalStrengthListener(SignalCallback callback) {
        this.callback = callback;
    }

    @Override
    public void onSignalStrengthsChanged(SignalStrength signalStrength) {
        super.onSignalStrengthsChanged(signalStrength);
        
        int dbm = -120; // Default "no signal"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Modern API (Android 10+)
            List<CellSignalStrength> cellStrengths = signalStrength.getCellSignalStrengths();
            if (!cellStrengths.isEmpty()) {
                // Get the first available (usually the primary) signal strength
                dbm = cellStrengths.get(0).getDbm();
            }
        } else {
            // Legacy approach
            if (signalStrength.isGsm()) {
                int asu = signalStrength.getGsmSignalStrength();
                if (asu != 99) {
                    dbm = (2 * asu) - 113;
                }
            } else {
                dbm = signalStrength.getCdmaDbm();
                if (dbm == -1) dbm = signalStrength.getEvdoDbm();
            }
        }
        
        // Sanity check: if dBm is reported as 0 or 2147483647 (Integer.MAX_VALUE), treat as -120
        if (dbm >= 0 || dbm == Integer.MAX_VALUE) {
            dbm = -120;
        }

        callback.onSignalUpdate(dbm);
    }
}
