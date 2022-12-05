#ifndef HAISHINKIT_KT_PIXELTRANSFORM_H
#define HAISHINKIT_KT_PIXELTRANSFORM_H

#include <jni.h>
#include <android/hardware_buffer_jni.h>
#include <media/NdkImageReader.h>
#include "Kernel.h"
#include "SurfaceRotation.hpp"
#include "ResampleFilter.h"
#include "ImageReader.h"
#include "FpsController.h"
#include "VideoEffect.h"

namespace Graphics {

    class PixelTransform {
    public:
        static void *OnRunning(void *data);

        static void OnFrame(long frameTimeNanos, void *data);

        PixelTransform();

        ~PixelTransform();

        void SetImageExtent(int32_t width, int32_t height);

        void SetImageReader(int32_t width, int32_t height, int32_t format);

        void SetVideoGravity(VideoGravity newVideoGravity);

        void SetResampleFilter(ResampleFilter newResampleFilter);

        void SetAssetManager(AAssetManager *assetManager);

        void SetNativeWindow(ANativeWindow *nativeWindow);

        void SetImageOrientation(ImageOrientation imageOrientation);

        void SetDeviceOrientation(SurfaceRotation surfaceRotation);

        void SetRotatesWithContent(bool rotatesWithContent);

        void SetFrameRate(int frameRate);

        void SetVideoEffect(VideoEffect *videoEffect);

        ANativeWindow *GetInputSurface();

        bool HasFeatures();

        void ReadPixels(void *buffer);

        std::string InspectDevices();

        void OnRunning();

        void OnFrame(long frameTimeNanos);

    private:
        std::vector<Texture *> textures;
        Kernel *kernel;
        VideoGravity videoGravity = RESIZE_ASPECT_FILL;
        ResampleFilter resampleFilter = LINEAR;
        ImageOrientation imageOrientation = UP;
        ImageReader *imageReader;
        FpsController *fpsController;
        pthread_t pthread{};
        std::mutex mutex;
        ALooper *looper = nullptr;
        AChoreographer *choreographer = nullptr;
        bool running = false;
        VideoEffect *videoEffect = nullptr;

        void StartRunning();

        void StopRunning();
    };
}

#endif //HAISHINKIT_KT_PIXELTRANSFORM_H
