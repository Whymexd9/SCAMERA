package com.particlesdevs.photoncamera.processing.color;

/**
 * P408 (owner, 2026-10-29: «Перенеси логику работы с CCM из арк»): the reference app's colour matrix profiles (its CameraCcmRegistry:
 * the LMC base matrices per sensor, white-balanced camera RGB -> linear sRGB) under their ids. Id 0 is the system matrix (the shot's
 * own colour route).
 */
public final class CcmPresets {
    public static final class Preset {
        public final int id;
        public final String name;
        final float[] m;

        Preset(int id, String name, float[] m) {
            this.id = id;
            this.name = name;
            this.m = m;
        }
    }

    public static final Preset[] ALL = {
            new Preset(4, "IMX686 (Redmi K30 Pro)", new float[]{1.81854f, -0.838371f, 0.0198339f, -0.246695f, 1.57214f, -0.325446f, -0.0111353f, -0.684763f, 1.6959f}),
            new Preset(5, "HMX (Mi Note 10)", new float[]{1.881f, -0.8795f, -0.0015f, -0.2836f, 1.6469f, -0.3633f, -0.0367f, -0.7185f, 1.7552f}),
            new Preset(6, "IMX682", new float[]{1.55495f, -0.47328f, -0.0816703f, -0.0707253f, 1.22384f, -0.15311f, 0.100972f, -0.771204f, 1.67023f}),
            new Preset(7, "Black and White", new float[]{1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f}),
            new Preset(8, "Default Fix", new float[]{1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f}),
            new Preset(9, "S5KGM2 (Redmi Note 9s)", new float[]{1.67969f, -0.9375f, 0.238f, -0.148438f, 1.043f, 0.0859375f, 0.119629f, -0.914062f, 1.855f}),
            new Preset(10, "CT Mod 2", new float[]{1.2987f, -0.1938f, -0.0977f, -0.2486f, 1.5532f, -0.2974f, -0.05f, -0.3594f, 1.3951f}),
            new Preset(11, "S5KGW3 Mi11 Lite", new float[]{1.8126f, -0.867188f, 0.054688f, -0.226562f, 1.32812f, -0.101562f, -0.0625f, -0.546875f, 1.61719f}),
            new Preset(12, "HMX Mi10T Pro", new float[]{1.75f, -0.828125f, 0.078125f, -0.1875f, 1.26562f, -0.070312f, -0.039062f, -0.523438f, 1.5625f}),
            new Preset(13, "Realme 5 pro", new float[]{1.67969f, -0.9375f, 0.238f, -0.148438f, 1.043f, 0.0859375f, 0.119629f, -0.914062f, 1.81f}),
            new Preset(14, "HMX Mi Note 10 NEW", new float[]{1.60938f, -0.609375f, 0.007812f, -0.132812f, 1.32031f, -0.1875f, -0.007812f, -0.648438f, 1.66406f}),
            new Preset(15, "IMX582 Mi9T", new float[]{1.59375f, -0.632812f, 0.039062f, -0.195312f, 1.30469f, -0.101562f, 0.054688f, -0.671875f, 1.61719f}),
            new Preset(16, "IMX06a_Main (Vivo X100U)", new float[]{1.33196f, -0.35624f, 0.0242785f, -0.132699f, 1.25538f, -0.122682f, 0.0367231f, -0.546867f, 1.51014f}),
            new Preset(17, "S5KHP9SP_Tele (Vivo X100U)", new float[]{1.42074f, -0.374253f, -0.0464917f, -0.219896f, 1.32911f, -0.109211f, 0.0098546f, -0.738836f, 1.72898f}),
            new Preset(18, "IMX882_UW (Vivo X100U)", new float[]{1.41068f, -0.386639f, -0.0240416f, -0.183782f, 1.32147f, -0.137692f, 0.0116854f, -0.723611f, 1.71193f}),
            new Preset(19, "S5KJN1_Front (Vivo X100U)", new float[]{1.75671f, -0.643467f, -0.113246f, -0.335244f, 1.61431f, -0.279069f, -0.171346f, -0.752475f, 1.92382f}),
            new Preset(20, "IMX989_Main (OPPO X6 Pro)", new float[]{1.57545f, -0.558346f, -0.0171037f, -0.197686f, 1.20723f, -0.0095411f, 0.009476f, -0.630767f, 1.62129f}),
            new Preset(21, "IMX890_Tele (OPPO X6 Pro)", new float[]{1.51551f, -0.518746f, 0.0032344f, -0.308724f, 1.3196f, -0.0108781f, -0.0434162f, -0.706896f, 1.75031f}),
            new Preset(22, "IMX890_UW (OPPO X6 Pro)", new float[]{1.57541f, -0.629435f, 0.0540226f, -0.241543f, 1.228f, 0.0135455f, -0.0039895f, -0.713884f, 1.71787f}),
            new Preset(23, "IMX709_Front (OPPO X6 Pro)", new float[]{1.86041f, -0.614231f, -0.246176f, -0.26919f, 1.54719f, -0.194907f, -0.233649f, -0.538697f, 1.91524f}),
            new Preset(24, "IMX06A_Main (OPPO X7U)", new float[]{1.59753f, -0.600736f, 0.003206f, -0.172655f, 1.38043f, -0.207772f, 0.038647f, -0.574189f, 1.53554f}),
            new Preset(25, "IMX890_Tele (OPPO X7U)", new float[]{1.52459f, -0.233887f, -0.290703f, -0.262018f, 1.61035f, -0.348335f, 0.020139f, -0.687166f, 1.66703f}),
            new Preset(26, "IMX882_UW (OPPO X7U)", new float[]{1.10746f, -0.178978f, 0.071518f, -0.129029f, 1.13725f, -0.008217f, -0.033333f, -0.780467f, 1.8138f}),
            new Preset(27, "IMX858_Tele2 (OPPO X7U)", new float[]{1.5114f, -0.186812f, -0.324589f, -0.140752f, 1.5236f, -0.382851f, -0.014582f, -0.592466f, 1.60705f}),
            new Preset(28, "IMX709_Front (OPPO X7U)", new float[]{2.104f, -1.0285f, -0.0755f, -0.2898f, 1.8653f, -0.5755f, -0.152f, -0.7032f, 1.8552f}),
            new Preset(29, "IMX06c_Main (Vivo X200U)", new float[]{1.67996f, -0.925398f, 0.14669f, -0.113992f, 1.25587f, -0.0655392f, -0.16176f, -0.558025f, 1.64053f}),
            new Preset(30, "S5KHP9SP_Tele (Vivo X200U)", new float[]{1.74293f, -0.866201f, 0.169794f, -0.127478f, 1.18298f, -0.0262656f, 0.0094385f, -0.685623f, 1.6333f}),
            new Preset(31, "IMX06c_UW (Vivo X200U)", new float[]{1.74448f, -0.889576f, 0.151025f, -0.117949f, 1.20347f, -0.0278758f, 0.0312887f, -0.622217f, 1.61001f}),
            new Preset(32, "S5KJN1_Front (Vivo X200U)", new float[]{1.73241f, -0.822533f, 0.155914f, -0.109894f, 1.18428f, -0.0264006f, 0.0345096f, -0.867373f, 1.46049f}),
            new Preset(33, "IMX921_Main (IQOO NEO 10)", new float[]{1.70289f, -0.771472f, 0.150065f, -0.111812f, 1.21891f, -0.0223702f, -0.0329756f, -0.562772f, 1.76987f}),
            new Preset(34, "OV08D10_UW (IQOO NEO 10)", new float[]{1.65628f, -0.741685f, 0.15786f, -0.112719f, 1.22304f, -0.0230493f, 0.0388352f, -0.669019f, 1.64533f}),
            new Preset(35, "S5K3P9SP_Front (IQOO NEO 10)", new float[]{1.46794f, -0.134742f, 0.0468309f, -0.122965f, 1.16113f, -0.0115509f, 0.0070087f, -0.833237f, 1.73298f}),
            new Preset(36, "IMX989_Main (13Ultra)", new float[]{1.46571f, -0.175331f, 0.0083901f, -0.127085f, 1.25415f, -0.0655754f, 0.0514577f, -0.527736f, 1.56757f}),
            new Preset(37, "IMX858_Tele3x (13Ultra)", new float[]{1.48567f, -0.210348f, 0.0132031f, -0.127007f, 1.2486f, -0.0447627f, 0.0423233f, -0.560639f, 1.58184f}),
            new Preset(38, "IMX858_UW (13Ultra)", new float[]{1.45333f, -0.181236f, 0.0196694f, -0.122674f, 1.21476f, -0.0363982f, 0.0187202f, -0.653361f, 1.62023f}),
            new Preset(39, "IMX858_Tele5x (13Ultra)", new float[]{1.60624f, -0.41187f, 0.0033573f, -0.113687f, 1.09792f, -0.0217224f, -0.0138015f, -0.596353f, 1.91771f}),
            new Preset(40, "OV32C_Front (13Ultra)", new float[]{1.64379f, -0.585706f, 0.183642f, -0.111455f, 1.09304f, -0.0236653f, -0.0176018f, -0.530883f, 1.85038f}),
            new Preset(41, "OV50A_Main (Moto Edge 30)", new float[]{1.70697f, -0.856051f, 0.16067f, -0.117547f, 1.19735f, -0.0179588f, 0.0027879f, -0.723623f, 1.60472f}),
            new Preset(42, "S5KJN1_UW (Moto Edge 30)", new float[]{1.73423f, -0.891063f, 0.145628f, -0.117089f, 1.15395f, -0.0397936f, 0.0390287f, -0.84544f, 1.45355f}),
            new Preset(43, "OV32B40_Front (Moto Edge 30)", new float[]{1.68909f, -0.695094f, 0.180421f, -0.0962803f, 1.06934f, -0.03011f, 0.0301887f, -0.752236f, 1.66802f}),
            new Preset(44, "S5KHMX_Main (Mi10TPro)", new float[]{1.69329f, -0.778234f, 0.137246f, -0.11488f, 1.24442f, -0.0241442f, -0.0489813f, -0.631289f, 1.81207f}),
            new Preset(45, "S5K5E9YX_Macro (Mi10TPro)", new float[]{1.48114f, -0.277449f, 0.0131635f, -0.107918f, 1.19377f, -0.0314063f, 0.0083125f, -0.748253f, 1.58796f}),
            new Preset(46, "OV13B10_UW (Mi10TPro)", new float[]{1.68024f, -0.713695f, 0.17192f, -0.108088f, 1.14077f, -0.0311391f, 0.0381002f, -0.801041f, 1.63273f}),
            new Preset(47, "S5K3T2_Front (Mi10TPro)", new float[]{1.50947f, -0.383599f, 0.0097816f, -0.119644f, 1.12232f, -0.0087776f, 0.0096017f, -0.720195f, 1.76859f}),
            new Preset(48, "S5KHMX_Main (Mi10Pro)", new float[]{1.61231f, -0.707685f, -0.0792439f, -0.115694f, 1.11532f, -0.0082739f, 0.0191018f, -0.737863f, 1.65863f}),
            new Preset(49, "S5K2l7_Tele2x (Mi10Pro)", new float[]{1.4855f, -0.288013f, 0.0095003f, -0.112709f, 1.19873f, -0.030349f, 0.0487765f, -0.508982f, 1.60486f}),
            new Preset(50, "IMX350_UW (Mi10Pro)", new float[]{1.71342f, -0.824138f, 0.173284f, -0.108122f, 1.10581f, -0.0290596f, -0.0359099f, -0.6682f, 1.75811f}),
            new Preset(51, "OV08A10_Tele4x (Mi10Pro)", new float[]{1.21202f, -0.114021f, 0.0372916f, -0.108936f, 1.02249f, -0.0067868f, -0.103436f, -0.704708f, 2.52277f}),
            new Preset(52, "S5K3T2_Front (Mi10Pro)", new float[]{1.70363f, -0.839994f, 0.152258f, -0.113061f, 1.2597f, -0.0284731f, 0.0417681f, -0.55679f, 1.81292f}),
            new Preset(53, "S5KGN2_Main (11Ultra)", new float[]{1.72697f, -0.833283f, 0.142386f, -0.104872f, 1.24863f, -0.0609492f, 0.0842836f, -0.616634f, 1.69465f}),
            new Preset(54, "IMX586_Tele (11Ultra)", new float[]{1.71338f, -0.784952f, 0.156803f, -0.107164f, 1.22333f, -0.0321222f, 0.040004f, -0.846409f, 1.9649f}),
            new Preset(55, "IMX586_UW (11Ultra)", new float[]{1.68883f, -0.718417f, 0.17206f, -0.113554f, 1.11324f, -0.025466f, 0.0572868f, -0.796565f, 1.77938f}),
            new Preset(56, "S5K3T2_Front (11Ultra)", new float[]{1.73362f, -0.954165f, 0.083707f, -0.0974495f, 1.30736f, -0.0353554f, 0.0593441f, -0.628544f, 1.77575f}),
            new Preset(57, "IMX882_Main (Realme 12Pro)", new float[]{1.74564f, -0.794775f, 0.170992f, -0.123733f, 1.17581f, -0.0404839f, 0.0180453f, -0.660687f, 1.69574f}),
            new Preset(58, "IMX709_Tele (Realme 12Pro)", new float[]{1.7292f, -0.806421f, 0.151353f, -0.101804f, 1.16812f, -0.0464149f, 0.0549533f, -0.591054f, 1.85717f}),
            new Preset(59, "HI846W_UW (Realme 12Pro)", new float[]{1.68817f, -0.758005f, 0.128882f, -0.116197f, 1.32605f, -0.0246528f, -0.0084389f, -0.553504f, 1.9517f}),
            new Preset(60, "HI1634Q_Front (Realme 12Pro)", new float[]{1.47876f, -0.214689f, 0.045976f, -0.101216f, 1.2029f, -0.0560796f, 0.0151502f, -0.710116f, 1.90518f}),
            new Preset(61, "IMX890_Main (Realme 13Pro+)", new float[]{1.44415f, -0.205682f, 0.0397438f, -0.113593f, 1.22544f, -0.0243077f, 0.0240329f, -0.669992f, 1.80825f}),
            new Preset(62, "IMX882_Tele (Realme 13Pro+)", new float[]{1.69551f, -0.606036f, 0.167031f, -0.108986f, 1.13879f, -0.0412977f, 0.0368577f, -0.710988f, 1.7358f}),
            new Preset(63, "SC820_UW (Realme 13Pro+)", new float[]{1.32171f, -0.119889f, 0.0812183f, -0.0657324f, 1.14907f, -0.0305039f, 0.029772f, -0.675769f, 1.67512f}),
            new Preset(64, "IMX615_Front (Realme 13Pro+)", new float[]{1.94225f, -1.39841f, -0.0198559f, -0.0906197f, 1.58654f, -0.249144f, -0.0137102f, -0.732165f, 1.91208f}),
            new Preset(65, "LYT-T808_Main (OnePlus 13)", new float[]{1.88713f, -1.19334f, -0.0555908f, -0.105657f, 1.4486f, -0.191362f, -0.0134762f, -0.548f, 1.95285f}),
            new Preset(66, "LYT-600_Tele2 (OnePlus 13)", new float[]{1.90776f, -0.908881f, -0.64312f, -0.111313f, 1.58155f, -0.14332f, -0.459881f, -0.638985f, 1.7914f}),
            new Preset(67, "S5KJN5_UW (OnePlus 13)", new float[]{1.88047f, -0.962481f, -0.263611f, -0.0799631f, 1.45448f, -0.1567f, -0.369422f, -0.614084f, 1.8234f}),
            new Preset(68, "IMX615_Front (OnePlus 13)", new float[]{1.23602f, -0.196025f, 0.139937f, -0.0793154f, 1.47484f, -0.0674348f, 0.0565343f, -0.573658f, 1.19256f}),
            new Preset(69, "IMX906_Main (OnePlus 15)", new float[]{1.20766f, -0.171059f, 0.101366f, -0.0414042f, 1.35717f, -0.0370289f, 0.0567552f, -0.578796f, 1.44924f}),
            new Preset(70, "S5KJN5_Tele (OnePlus 15)", new float[]{1.01611f, -0.116325f, 0.206819f, -0.0447221f, 1.37002f, -0.0349193f, 0.0388075f, -0.532277f, 1.95269f}),
            new Preset(71, "OV50D40_UW (OnePlus 15)", new float[]{1.36082f, -0.184192f, 0.0600716f, -0.102288f, 1.47942f, -0.105643f, -0.0344007f, -0.627739f, 1.22568f}),
            new Preset(72, "IMX709_Front (OnePlus 15)", new float[]{1.28826f, -0.198927f, 0.0400924f, -0.092307f, 1.60113f, -0.0830039f, 0.0525131f, -0.643529f, 1.02922f}),
            new Preset(73, "LYT900_Main (15Ultra)", new float[]{1.29644f, -0.212182f, 0.0490576f, -0.058654f, 1.59657f, -0.0859208f, 0.0287784f, -0.580131f, 1.17466f}),
            new Preset(74, "IMX858_Tele3x (15Ultra)", new float[]{1.18235f, -0.187737f, 0.0928083f, -0.0595576f, 1.47782f, -0.0903816f, 0.0487555f, -0.528675f, 1.70418f}),
            new Preset(75, "S5KJN5_UW (15Ultra)", new float[]{1.0245f, -0.150549f, 0.140278f, -0.0543987f, 1.39508f, -0.0398908f, 0.0509151f, -0.722789f, 1.52053f}),
            new Preset(76, "S5KHP9_Tele5x (15Ultra)", new float[]{1.26445f, -0.215029f, 0.0196916f, -0.0748225f, 1.62157f, -0.12412f, 0.0497519f, -0.601942f, 0.972763f}),
            new Preset(77, "OV32B40_Front (15Ultra)", new float[]{1.25045f, -0.192121f, 0.1003f, -0.0639299f, 1.49158f, -0.0563117f, 0.0107851f, -0.791139f, 1.74532f}),
            new Preset(78, "IMX896_Main (Realme 14Pro+)", new float[]{0.999787f, -0.112845f, 0.209813f, -0.0591777f, 1.5441f, -0.0113098f, 0.160119f, -0.818712f, 1.03702f}),
            new Preset(79, "IMX882_Tele (Realme 14Pro+)", new float[]{1.15531f, -0.208459f, 0.0263788f, -0.0752733f, 1.72507f, -0.0917647f, 0.0420864f, -0.637506f, 0.963317f}),
            new Preset(80, "SC201CS_UW (Realme 14Pro+)", new float[]{1.16453f, -0.177429f, 0.149596f, -0.0524445f, 1.46215f, -0.0288221f, 0.0429908f, -0.842389f, 1.9134f}),
            new Preset(81, "IMX480_Front (Realme 14Pro+)", new float[]{1.22625f, -0.218319f, 0.155237f, -0.0570808f, 1.55737f, -0.0296935f, 0.0685389f, -0.54299f, 1.24232f}),
            new Preset(82, "IMX682_Main (Poco X3NFC)", new float[]{1.08f, -0.153154f, 0.17802f, -0.0500018f, 1.49248f, -0.0238654f, 0.126071f, -0.492967f, 1.12129f}),
            new Preset(83, "HI259_Macro (Poco X3NFC)", new float[]{1.67474f, -0.78468f, 0.132428f, -0.103821f, 1.0545f, -0.0074066f, 0.0349416f, -0.845582f, 1.83761f}),
            new Preset(84, "HI1337_UW (Poco X3NFC)", new float[]{1.31719f, -0.187831f, 0.138921f, -0.061952f, 1.47202f, -0.0371271f, 0.0505242f, -0.64102f, 1.7004f}),
            new Preset(85, "S5K3T2_Front (Poco X3NFC)", new float[]{1.0166f, -0.144353f, 0.166072f, -0.0785249f, 1.71499f, -0.0134136f, 0.142659f, -0.691094f, 1.04511f}),
            new Preset(86, "IMX582_Main (Poco X3Pro)", new float[]{1.70407f, -0.791053f, 0.160265f, -0.101995f, 1.19942f, -0.0331731f, 0.046888f, -0.737301f, 1.58962f}),
            new Preset(87, "HI259_Macro (Poco X3Pro)", new float[]{1.66141f, -0.79985f, -0.134277f, -0.106598f, 1.27803f, -0.0608476f, 0.103498f, -0.580607f, 1.78118f}),
            new Preset(88, "OV8856_UW (Poco X3Pro)", new float[]{1.68868f, -0.659182f, 0.187252f, -0.109563f, 1.21433f, -0.027642f, 0.0658128f, -0.490351f, 1.76945f}),
            new Preset(89, "S5K3T2_Front (Poco X3Pro)", new float[]{1.16855f, -0.191081f, 0.168333f, -0.106095f, 1.30512f, -0.0290167f, 0.148579f, -0.669284f, 1.62268f}),
            new Preset(90, "IMX890_Main (Realme 12Pro+)", new float[]{1.72962f, -0.82212f, 0.150097f, -0.0983542f, 1.15434f, -0.0457603f, 0.0609455f, -0.501043f, 1.66363f}),
            new Preset(91, "OV64B_Tele (Realme 12Pro+)", new float[]{1.20867f, -0.139965f, 0.164051f, -0.0816589f, 1.40696f, -0.0328741f, 0.071009f, -0.588017f, 1.51257f}),
            new Preset(92, "HI846W_UW (Realme 12Pro+)", new float[]{1.0164f, -0.13009f, 0.181352f, -0.0646752f, 1.50715f, -0.0277514f, 0.0928133f, -0.527443f, 1.09553f}),
            new Preset(93, "IMX615_Front (Realme 12Pro+)", new float[]{1.89898f, -0.54933f, 0.15492f, -0.208283f, 1.5425f, -0.255154f, -0.0500943f, -0.668156f, 1.53909f}),
            new Preset(94, "IMX766_Main (Oppo Find X3Pro)", new float[]{1.58822f, -0.521363f, -0.199171f, -0.181525f, 1.50564f, -0.040697f, 0.0847341f, -0.837778f, 1.00263f}),
            new Preset(95, "S5K3M5_Tele (Oppo Find X3Pro)", new float[]{1.46765f, -0.4022f, -0.0654503f, -0.200445f, 1.23575f, -0.0353029f, -0.0384155f, -0.550905f, 1.58932f}),
            new Preset(96, "IMX766_UW (Oppo Find X3Pro)", new float[]{1.34426f, -0.358749f, 0.014487f, -0.146638f, 1.16462f, -0.0179787f, -0.0186188f, -0.646038f, 1.66466f}),
            new Preset(97, "IMX616_Front (Oppo Find X3Pro)", new float[]{1.72968f, -0.887445f, 0.15776f, -0.119401f, 1.17394f, -0.0545347f, 0.0786159f, -0.648955f, 1.57034f})};

    private CcmPresets() { }

    /** The matrix of profile {@code id} (a copy), null for 0 or an unknown id. */
    public static float[] matrix(int id) {
        for (Preset p : ALL) if (p.id == id) return p.m.clone();
        return null;
    }

    /**
     * The reference's Dynamic CCT (CameraCcmRegistry.applyDynamicCct): above ISO 400 each row of {@code m} goes towards its sum on the
     * diagonal by t = log16(ISO / 400) (ISO held in 400 .. 6400) x {@code strength} - the matrix's colour mixing (and its noise) fades,
     * a grey stays grey. {@code m} itself when nothing changes.
     */
    public static float[] dynamicCct(float[] m, int iso, float strength) {
        if (m == null || m.length != 9 || strength <= 0.001f || iso <= 400) return m;
        final float t = (float) (Math.max(0.0, Math.min(1.0, Math.log(Math.max(400f, Math.min(6400f, iso)) / 400.0) / Math.log(16.0)))
                * Math.max(0f, Math.min(1f, strength)));
        if (t <= 0.001f) return m;
        final float[] out = new float[9];
        for (int r = 0; r < 3; r++) {
            final float sum = m[r * 3] + m[r * 3 + 1] + m[r * 3 + 2];
            for (int c = 0; c < 3; c++) out[r * 3 + c] = m[r * 3 + c] * (1f - t) + (r == c ? sum * t : 0f);
        }
        return out;
    }

    /** A 3 x 3 matrix written as nine numbers (commas, spaces or semicolons between them), null when it is not one. */
    public static float[] parse(String text) {
        if (text == null) return null;
        final String[] parts = text.trim().split("[,;\\s]+");
        if (parts.length != 9) return null;
        final float[] m = new float[9];
        try {
            for (int i = 0; i < 9; i++) {
                m[i] = Float.parseFloat(parts[i]);
                if (Float.isNaN(m[i]) || Float.isInfinite(m[i])) return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return m;
    }
}
