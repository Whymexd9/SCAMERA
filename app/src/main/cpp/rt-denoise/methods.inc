static void Median_Denoise(float **src, float **dst, int width, int height, Median medianType, int iterations, int numThreads, float **buffer = nullptr);
void Median_Denoise(float **src, float **dst, float upperBound, int width, int height, Median medianType, int iterations, int numThreads, float **buffer = nullptr);
void Tile_calc(int tilesize, int overlap, int kall, int imwidth, int imheight, int &numtiles_W, int &numtiles_H, int &tilewidth, int &tileheight, int &tileWskip, int &tileHskip);
void RGB_denoise(int kall, Imagefloat * src, Imagefloat * dst, Imagefloat * calclum, float * ch_M, float *max_r, float *max_b, bool isRAW, const procparams::DirPyrDenoiseParams & dnparams, const double expcomp, const NoiseCurve & noiseLCurve, const NoiseCurve & noiseCCurve, float &nresi, float &highresi);
void RGBtile_denoise(float * fLblox, int hblproc, float noisevar_Ldetail);
void RGBoutput_tile_row(float *bloxrow_L, float ** Ldetail, float ** tilemask_out, int height, int width, int top);
float Mad(const float * DataList, int datalen);
float MadRgb(const float * DataList, int datalen);
void Noise_residualAB(const wavelet_decomposition &WaveletCoeffs_ab, float &chresid, float &chmaxresid, bool denoiseMethodRgb, int beg, int end);
bool WaveletDenoiseAll_BiShrinkL(wavelet_decomposition& WaveletCoeffs_L, float *noisevarlum, float madL[8][3], float * vari, int edge, int denoiseNestedLevels);
bool WaveletDenoiseAll_BiShrinkAB(wavelet_decomposition& WaveletCoeffs_L, wavelet_decomposition& WaveletCoeffs_ab, float *noisevarchrom, float madL[8][3], float *variC, int local, float noisevar_ab, const bool useNoiseCCurve,  bool autoch, bool denoiseMethodRgb, int denoiseNestedLevels);
bool WaveletDenoiseAllL(wavelet_decomposition& WaveletCoeffs_L, float *noisevarlum, float madL[8][3], float * vari, int edge, int denoiseNestedLevels);
bool WaveletDenoiseAllAB(wavelet_decomposition& WaveletCoeffs_L, wavelet_decomposition& WaveletCoeffs_ab, float *noisevarchrom, float madL[8][3], float *variC, int local, float noisevar_ab, const bool useNoiseCCurve, bool autoch, bool denoiseMethodRgb, int denoiseNestedLevels);
void ShrinkAllL(wavelet_decomposition& WaveletCoeffs_L, float **buffer, int level, int dir, float *noisevarlum, float * madL, float * vari, int edge);
void ShrinkAllAB(wavelet_decomposition& WaveletCoeffs_L, wavelet_decomposition& WaveletCoeffs_ab, float **buffer, int level, int dir,
                     float *noisevarchrom, float noisevar_ab, const bool useNoiseCCurve, bool autoch, bool denoiseMethodRgb, float * madL, float * variC, int local, float * madaab = nullptr, bool madCalculated = false);
void ShrinkAll_info(const float* const* WavCoeffs_a, const float* const* WavCoeffs_b,
                        int W_ab, int H_ab, float **noisevarlum, float **noisevarchrom, float **noisevarhue, float &chaut, int &Nb, float &redaut, float &blueaut, float &maxredaut, float &maxblueaut, float &minredaut, float &minblueaut, int schoice, int lvl, float &chromina, float &sigma, float &lumema, float &sigma_L, float &redyel, float &skinc, float &nsknc,
                        float &maxchred, float &maxchblue, float &minchred, float &minchblue, int &nb, float &chau, float &chred, float &chblue, bool denoiseMethodRgb);
void WaveletDenoiseAll_info(int levwav, const wavelet_decomposition &WaveletCoeffs_a,
                                const wavelet_decomposition &WaveletCoeffs_b, float **noisevarlum, float **noisevarchrom, float **noisevarhue, float &chaut, int &Nb, float &redaut, float &blueaut, float &maxredaut, float &maxblueaut, float &minredaut, float & minblueaut, int schoice, float &chromina, float &sigma, float &lumema, float &sigma_L, float &redyel, float &skinc, float &nsknc,
                                float &maxchred, float &maxchblue, float &minchred, float &minchblue, int &nb, float &chau, float &chred, float &chblue, bool denoiseMethodRgb);
void RGB_denoise_infoGamCurve(const procparams::DirPyrDenoiseParams & dnparams, const bool isRAW, LUTf &gamcurve, float &gam, float &gamthresh, float &gamslope);
void calcautodn_info(float &chaut, float &delta, int Nb, int levaut, float maxmax, float lumema, float chromina, int mode, int lissage, float redyel, float skinc, float nsknc);
void RGB_denoise_info(Imagefloat * src, Imagefloat * provicalc, bool isRAW, const LUTf &gamcurve, float gam, float gamthresh, float gamslope, const procparams::DirPyrDenoiseParams & dnparams, const double expcomp, float &chaut, int &Nb, float &redaut, float &blueaut, float &maxredaut, float & maxblueaut, float &minredaut, float & minblueaut, float &chromina, float &sigma, float &lumema, float &sigma_L, float &redyel, float &skinc, float &nsknc, bool multiThread = false);
