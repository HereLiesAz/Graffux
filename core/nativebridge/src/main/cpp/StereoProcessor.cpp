#include "StereoProcessor.h"
#include <android/log.h>

#define LOG_TAG "StereoProcessor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

StereoProcessor::StereoProcessor() {
    // Initialize the semi-global block matching algorithm
    mStereoMatcher = cv::StereoSGBM::create(
            0,    // minDisparity
            64,   // numDisparities
            11,   // blockSize
            8 * 3 * 11 * 11,   // P1
            32 * 3 * 11 * 11,  // P2
            1,    // disp12MaxDiff
            63,   // preFilterCap
            10,   // uniquenessRatio
            100,  // speckleWindowSize
            32    // speckleRange
    );
}

StereoProcessor::~StereoProcessor() {
    // OpenCV Smart Pointers handle cleanup
}

void StereoProcessor::processStereo(int8_t* leftData, int8_t* rightData, int width, int height) {
    // Create OpenCV Mats from raw pointers
    cv::Mat leftFrame = cv::Mat(height, width, CV_8UC1, leftData);
    cv::Mat rightFrame = cv::Mat(height, width, CV_8UC1, rightData);

    if (leftFrame.empty() || rightFrame.empty()) {
        LOGI("Empty frames passed to StereoProcessor");
        return;
    }

    // Compute disparity
    mStereoMatcher->compute(leftFrame, rightFrame, mDisparityMap);

    LOGI("Stereo processing complete for %dx%d frame", width, height);
}

cv::Mat StereoProcessor::getDisparityMap() const {
    return mDisparityMap;
}