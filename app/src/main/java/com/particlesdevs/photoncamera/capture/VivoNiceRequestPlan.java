package com.particlesdevs.photoncamera.capture;

import android.hardware.camera2.CaptureRequest;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Value bridge from buildNiceHdrCapture to the verified stock request fields.
 * Not a complete request: VCF routing, scene/motion context, surfaces and the
 * matching processing graph must be supplied by the capture integration.
 * In particular this does not translate EV codes into manual shutter/ISO.
 */
public final class VivoNiceRequestPlan {
    public static final int PAYLOAD_BYTES = 448;
    private static final CaptureRequest.Key<float[]> AEC = new CaptureRequest.Key<>(
            "vivo.parameter.VivoAlgoAECFrameControl", float[].class);
    private static final CaptureRequest.Key<int[]> CONTROL = new CaptureRequest.Key<>(
            "vivo.parameter.VivoAlgoCaptureFrameControl", int[].class);
    private static final CaptureRequest.Key<int[]> RAW_HDR = new CaptureRequest.Key<>(
            "vivo.parameter.rawHDRParams", int[].class);
    private static final CaptureRequest.Key<Integer[]> COUNTS = new CaptureRequest.Key<>(
            "vivo.control.RequestLeftInThisSnapshot", Integer[].class);

    private final byte[] payload;
    private final float[] aec;
    private final int[] control, rawHdr;
    public final int pastCount, futureCount, frameCount;

    private VivoNiceRequestPlan(byte[] bytes) {
        payload = bytes;
        ByteBuffer in = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        aec = new float[48];
        for (int i=0; i<aec.length; ++i) aec[i]=in.getFloat();
        in.position(388); // Preserve short-AEC including its integer tail in payload.
        control = new int[9];
        for (int i=0; i<control.length; ++i) control[i]=in.getInt();
        pastCount=control[0]; futureCount=control[1];
        if (pastCount<0 || pastCount>16 || futureCount<0 || futureCount>16-pastCount
                || pastCount+futureCount==0)
            throw new IllegalArgumentException("Invalid NICE capture counts");
        frameCount=pastCount+futureCount;
        // This bridge accepts the recovered NICE branch, not unrelated vendor
        // batch layouts that happen to use the same key name.
        for (int i=2; i<control.length; ++i)
            if (control[i]!=0) throw new IllegalArgumentException("Unsupported NICE batch layout");
        rawHdr = new int[]{in.getInt(),in.getInt(),in.getInt()};
        for (int i=0; i<futureCount; ++i) {
            for (int block=0; block<3; ++block) {
                int offset=4*(16*block+i);
                if (!Float.isFinite(in.getFloat(offset)) || !Float.isFinite(in.getFloat(192+offset)))
                    throw new IllegalArgumentException("Nonfinite active NICE exposure");
            }
        }
        if (!Float.isFinite(in.getFloat(444)))
            throw new IllegalArgumentException("Nonfinite NICE capture DRC");
    }

    /** Copies exactly one native payload; never retains a mutable JNI buffer. */
    public static VivoNiceRequestPlan decode(ByteBuffer source) {
        if (source == null || source.remaining()!=PAYLOAD_BYTES)
            throw new IllegalArgumentException("NICE request needs exactly 448 bytes");
        byte[] copy=new byte[PAYLOAD_BYTES];
        source.duplicate().get(copy);
        return new VivoNiceRequestPlan(copy);
    }

    /** Includes fields for downstream processing, without coercing int bits to float. */
    public byte[] copyPayload() { return payload.clone(); }

    public boolean usesZsl(int index) {
        checkIndex(index);
        return index<pastCount;
    }

    /** Apply only the fields proven by executeRawVifVivoRawHdrCommand.
     * Use a fresh, unpublished builder. An unsupported vendor key propagates
     * failure; callers must discard the builder rather than submit half a plan.
     * No sensor mode, AE-mode or manual exposure setting is introduced here.
     */
    public void applyExposureFields(CaptureRequest.Builder builder, int index) {
        checkIndex(index);
        if (builder==null) throw new IllegalArgumentException("Missing request builder");
        builder.set(AEC,aec.clone());
        builder.set(CONTROL,control.clone());
        builder.set(RAW_HDR,rawHdr.clone());
        builder.set(COUNTS,index==0 ? new Integer[]{pastCount,futureCount} : new Integer[]{0,0});
        builder.set(CaptureRequest.CONTROL_ENABLE_ZSL,index<pastCount);
        builder.set(CaptureRequest.CONTROL_AE_LOCK,false);
    }

    private void checkIndex(int index) {
        if (index<0 || index>=frameCount) throw new IndexOutOfBoundsException("NICE request index");
    }
}
