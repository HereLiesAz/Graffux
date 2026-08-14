#pragma once

#include <GLES3/gl3.h>
#include <vector>
#include <glm/glm.hpp>
#include <mutex>

class ImageWarper {
public:
    ImageWarper();
    ~ImageWarper();

    void init();
    void setSourceImage(const uint8_t* data, int width, int height);
    void applyLiquify(const std::vector<glm::vec2>& stroke, float brushSize, float intensity);
    void draw(int viewportWidth, int viewportHeight);
    // outWidth/outHeight are the caller's actual output buffer dimensions (e.g. a locked Android
    // Bitmap's width/height), checked against the source image this warper was last given before
    // any pixels are read — mWidth/mHeight can change between a caller's bakeToBitmap call and its
    // own buffer's size being fixed, since this instance is a single shared object whose source can
    // be reset from another thread in between (see setSourceImage). Returns false, writing nothing,
    // on a mismatch or if no source has been set yet; true once outData has been fully written.
    bool bakeToBitmap(uint8_t* outData, int outWidth, int outHeight);
    void reset();

private:
    // Body of draw(); caller must hold mMutex (which is non-recursive, so
    // bakeToBitmap can't call the public draw() while holding the lock).
    void drawLocked(int viewportWidth, int viewportHeight);
    void initShaders();

    int mWidth = 0;
    int mHeight = 0;
    int mGridDim = 64;

    GLuint mProgram = 0;
    GLuint mTextureId = 0;
    GLuint mVboPos = 0;
    GLuint mVboTex = 0;
    GLuint mIbo = 0;
    GLuint mFbo = 0;
    GLuint mOffscreenTexture = 0;

    std::vector<glm::vec2> mVertices;
    std::vector<glm::vec2> mTexCoords;
    std::vector<uint32_t> mIndices;

    std::mutex mMutex;
    bool mInitialized = false;
    bool mMeshDirty = true;
};
