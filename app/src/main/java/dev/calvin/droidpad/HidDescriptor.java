package dev.calvin.droidpad;

import java.io.ByteArrayOutputStream;

/**
 * Builds a HID report descriptor for a multi-touch touchpad, and packs touch
 * reports. Structure mirrors a real HID-over-I2C precision touchpad so that
 * hid-multitouch (Linux) binds it and libinput treats it as a touchpad.
 *
 * Key requirements (verified against kernel + BlueZ sources):
 *  - top level application collection = Digitizer(0x0D) / Touch Pad(0x05)
 *  - contains Contact Identifier usage (0x0D/0x51) in an input field
 *    -> hid-core assigns HID_GROUP_MULTITOUCH -> hid-multitouch binds
 *  - Touch Pad application -> INPUT_MT_POINTER -> BTN_TOOL_FINGER/BTN_TOUCH/
 *    INPUT_PROP_POINTER, and ABS_MT_POSITION_X<->ABS_X copy
 *  - udev -> ID_INPUT_TOUCHPAD -> libinput touchpad
 */
public final class HidDescriptor {

    public static final int REPORT_ID = 1;
    public static final int MAX_CONTACTS = 5;

    /** bytes per input report: 5 fingers * 5 bytes + count + buttons */
    public static final int REPORT_BYTES = MAX_CONTACTS * 5 + 2;

    private HidDescriptor() {}

    private static void u8(ByteArrayOutputStream o, int v) { o.write(v & 0xff); }

    /**
     * @param physW physical width  in 0.1mm units (cm * 10^-2)
     * @param physH physical height in 0.1mm units
     */
    public static byte[] build(int physW, int physH) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(256);

        // ---- Usage Page (Digitizer), Usage (Touch Pad), Collection (Application)
        u8(o, 0x05); u8(o, 0x0D);
        u8(o, 0x09); u8(o, 0x05);
        u8(o, 0xA1); u8(o, 0x01);

        // Report ID 1
        u8(o, 0x85); u8(o, REPORT_ID);

        // ---- one logical collection per finger (PTP style) ----
        for (int i = 0; i < MAX_CONTACTS; i++) {
            u8(o, 0x05); u8(o, 0x0D);        // Usage Page (Digitizer)
            u8(o, 0x09); u8(o, 0x22);        // Usage (Finger)
            u8(o, 0xA1); u8(o, 0x02);        // Collection (Logical)

            // Tip Switch, 1 bit + 3 bit pad
            u8(o, 0x15); u8(o, 0x00);        // Logical Minimum 0
            u8(o, 0x25); u8(o, 0x01);        // Logical Maximum 1
            u8(o, 0x09); u8(o, 0x42);        // Usage (Tip Switch)
            u8(o, 0x75); u8(o, 0x01);        // Report Size 1
            u8(o, 0x95); u8(o, 0x01);        // Report Count 1
            u8(o, 0x81); u8(o, 0x02);        // Input (Data,Var,Abs)
            u8(o, 0x95); u8(o, 0x03);        // Report Count 3
            u8(o, 0x81); u8(o, 0x03);        // Input (Const)  -> pad to nibble

            // Contact Identifier, 4 bits
            u8(o, 0x25); u8(o, 0x0F);        // Logical Maximum 15
            u8(o, 0x09); u8(o, 0x51);        // Usage (Contact Identifier)
            u8(o, 0x75); u8(o, 0x04);        // Report Size 4
            u8(o, 0x95); u8(o, 0x01);        // Report Count 1
            u8(o, 0x81); u8(o, 0x02);        // Input (Data,Var,Abs)

            // X
            u8(o, 0x05); u8(o, 0x01);        // Usage Page (Generic Desktop)
            u8(o, 0x09); u8(o, 0x30);        // Usage (X)
            u8(o, 0x16); u8(o, 0x00); u8(o, 0x00);     // Logical Minimum 0
            u8(o, 0x26); u8(o, 0xFF); u8(o, 0x7F);     // Logical Maximum 32767
            u8(o, 0x36); u8(o, 0x00); u8(o, 0x00);     // Physical Minimum 0
            u8(o, 0x46); u8(o, physW & 0xFF); u8(o, (physW >> 8) & 0xFF); // Physical Max
            u8(o, 0x65); u8(o, 0x11);        // Unit (cm)
            u8(o, 0x55); u8(o, 0x0E);        // Unit Exponent (-2)
            u8(o, 0x75); u8(o, 0x10);        // Report Size 16
            u8(o, 0x95); u8(o, 0x01);        // Report Count 1
            u8(o, 0x81); u8(o, 0x02);        // Input

            // Y
            u8(o, 0x09); u8(o, 0x31);        // Usage (Y)
            u8(o, 0x36); u8(o, 0x00); u8(o, 0x00);
            u8(o, 0x46); u8(o, physH & 0xFF); u8(o, (physH >> 8) & 0xFF);
            u8(o, 0x75); u8(o, 0x10);
            u8(o, 0x95); u8(o, 0x01);
            u8(o, 0x81); u8(o, 0x02);

            u8(o, 0xC0);                     // End Collection (finger)
        }

        // ---- Contact Count ----
        u8(o, 0x05); u8(o, 0x0D);            // Usage Page (Digitizer)
        u8(o, 0x09); u8(o, 0x54);            // Usage (Contact Count)
        u8(o, 0x15); u8(o, 0x00);            // Logical Min 0
        u8(o, 0x25); u8(o, MAX_CONTACTS);    // Logical Max 5
        u8(o, 0x75); u8(o, 0x08);            // Report Size 8
        u8(o, 0x95); u8(o, 0x01);            // Report Count 1
        u8(o, 0x81); u8(o, 0x02);            // Input

        // ---- Buttons (left, right) + pad ----
        u8(o, 0x05); u8(o, 0x09);            // Usage Page (Button)
        u8(o, 0x19); u8(o, 0x01);            // Usage Minimum 1
        u8(o, 0x29); u8(o, 0x02);            // Usage Maximum 2
        u8(o, 0x15); u8(o, 0x00);            // Logical Min 0
        u8(o, 0x25); u8(o, 0x01);            // Logical Max 1
        u8(o, 0x75); u8(o, 0x01);            // Report Size 1
        u8(o, 0x95); u8(o, 0x02);            // Report Count 2
        u8(o, 0x81); u8(o, 0x02);            // Input
        u8(o, 0x95); u8(o, 0x06);            // Report Count 6
        u8(o, 0x81); u8(o, 0x03);            // Input (Const)

        u8(o, 0xC0);                         // End Collection (application)

        return o.toByteArray();
    }

    /**
     * Packs an input report (report id is conveyed out-of-band by HOGP, not
     * included in the payload).
     *
     * @param out       destination, length >= REPORT_BYTES
     * @param down      per-slot tip state
     * @param ids       per-slot contact id (0..15)
     * @param xs        per-slot X (0..32767)
     * @param ys        per-slot Y (0..32767)
     * @param count     number of active contacts (0..5)
     * @param left      left button
     * @param right     right button
     */
    public static void pack(byte[] out, boolean[] down, int[] ids,
                            int[] xs, int[] ys, int count,
                            boolean left, boolean right) {
        int off = 0;
        for (int i = 0; i < MAX_CONTACTS; i++) {
            int tip = down[i] ? 1 : 0;
            int cid = ids[i] & 0x0F;
            out[off++] = (byte) ((tip & 0x01) | (cid << 4));
            out[off++] = (byte) (xs[i] & 0xFF);
            out[off++] = (byte) ((xs[i] >> 8) & 0xFF);
            out[off++] = (byte) (ys[i] & 0xFF);
            out[off++] = (byte) ((ys[i] >> 8) & 0xFF);
        }
        out[off++] = (byte) (MAX_CONTACTS & 0xFF);
        out[off] = (byte) ((left ? 1 : 0) | (right ? 2 : 0));
    }
}
