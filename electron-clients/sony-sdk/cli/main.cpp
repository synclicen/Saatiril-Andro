// Sony Camera Remote SDK CLI Wrapper for Saatiril
// Commands:
//   list       — enumerate cameras, output JSON
//   liveview N — connect to camera N, get one live-view frame, output base64
//   capture N  — connect to camera N, trigger shutter, download photo, output base64
//
// Compiled with CMake + MSVC on CI. Uses Cr_Core.dll from Sony SDK.

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

// Windows-specific
#ifdef _WIN32
#include <windows.h>
using CrString = std::wstring;
#define CRSTR(s) L ## s
#else
using CrString = std::string;
#define CRSTR(s) s
#endif

#include "CameraRemote_SDK.h"
#include "IDeviceCallback.h"
#include "CrDebugString.h"

// Base64 encoding
static const char b64chars[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
static std::string base64_encode(const unsigned char* data, size_t len) {
    std::string result;
    result.reserve(((len + 2) / 3) * 4);
    for (size_t i = 0; i < len; i += 3) {
        size_t n = (len - i < 3) ? (len - i) : 3;
        unsigned int b0 = data[i];
        unsigned int b1 = (n > 1) ? data[i+1] : 0;
        unsigned int b2 = (n > 2) ? data[i+2] : 0;
        result += b64chars[(b0 >> 2) & 0x3F];
        result += b64chars[((b0 << 4) | (b1 >> 4)) & 0x3F];
        result += (n > 1) ? b64chars[((b1 << 2) | (b2 >> 6)) & 0x3F] : '=';
        result += (n > 2) ? b64chars[b2 & 0x3F] : '=';
    }
    return result;
}

// Minimal callback (no-op, just satisfies the interface)
class DummyCallback : public SCRSDK::IDeviceCallback {
public:
    void OnConnected(CRSDK::CrDeviceHandle) override {}
    void OnDisconnected(CRSDK::CrDeviceHandle, SCRSDK::CrInt32u) override {}
    void OnPropertyChanged(CRSDK::CrDeviceHandle, const SCRSDK::CrDeviceProperty*) override {}
    void OnLiveViewImageChanged(CRSDK::CrDeviceHandle, const SCRSDK::CrImageDataBlock*) override {}
    void OnError(CRSDK::CrDeviceHandle, SCRSDK::CrInt32u) override {}
};

// Wide to UTF-8 conversion
static std::string wide_to_utf8(const wchar_t* wstr) {
    if (!wstr) return "";
    int len = WideCharToMultiByte(CP_UTF8, 0, wstr, -1, nullptr, 0, nullptr, nullptr);
    if (len <= 0) return "";
    std::string result(len - 1, '\0');
    WideCharToMultiByte(CP_UTF8, 0, wstr, -1, &result[0], len, nullptr, nullptr);
    return result;
}

int main(int argc, char* argv[]) {
    if (argc < 2) {
        fprintf(stderr, "Usage: sony-cli <list|liveview|capture> [camera_index]\n");
        return 1;
    }

    std::string action = argv[1];

    // Initialize SDK
    if (!SCRSDK::Init()) {
        fprintf(stderr, "Failed to init Sony SDK\n");
        printf("{\"success\":false,\"error\":\"SDK init failed\"}\n");
        return 1;
    }

    if (action == "list") {
        // Enumerate cameras
        SCRSDK::ICrEnumCameraObjectInfo* enumInfo = nullptr;
        SCRSDK::CrError err = SCRSDK::EnumCameraObjects(&enumInfo, 3);
        if (err != SCRSDK::CrError_None || !enumInfo) {
            printf("{\"success\":false,\"error\":\"EnumCameraObjects failed: 0x%x\"}\n", err);
            SCRSDK::Release();
            return 1;
        }

        SCRSDK::CrInt32u count = enumInfo->GetCount();
        printf("{\"success\":true,\"cameras\":[");
        for (SCRSDK::CrInt32u i = 0; i < count; i++) {
            const SCRSDK::ICrCameraObjectInfo* cam = enumInfo->GetCameraObjectInfo(i);
            if (cam) {
                std::string name = wide_to_utf8(cam->GetName());
                std::string model = wide_to_utf8(cam->GetModel());
                SCRSDK::CrInt16 pid = cam->GetUsbPid();
                if (i > 0) printf(",");
                printf("{\"index\":%u,\"name\":\"%s\",\"model\":\"%s\",\"pid\":%d}",
                    (unsigned)i, name.c_str(), model.c_str(), (int)pid);
            }
        }
        printf("]}\n");
        enumInfo->Release();
        SCRSDK::Release();
        return 0;
    }

    if (action == "liveview" || action == "capture") {
        if (argc < 3) {
            fprintf(stderr, "Need camera index\n");
            SCRSDK::Release();
            return 1;
        }
        int camIndex = atoi(argv[2]);

        // Enumerate cameras
        SCRSDK::ICrEnumCameraObjectInfo* enumInfo = nullptr;
        SCRSDK::CrError err = SCRSDK::EnumCameraObjects(&enumInfo, 3);
        if (err != SCRSDK::CrError_None || !enumInfo) {
            printf("{\"success\":false,\"error\":\"EnumCameraObjects failed\"}\n");
            SCRSDK::Release();
            return 1;
        }

        SCRSDK::CrInt32u count = enumInfo->GetCount();
        if (camIndex < 0 || camIndex >= (int)count) {
            printf("{\"success\":false,\"error\":\"Camera index out of range\"}\n");
            enumInfo->Release();
            SCRSDK::Release();
            return 1;
        }

        const SCRSDK::ICrCameraObjectInfo* camInfo = enumInfo->GetCameraObjectInfo(camIndex);
        if (!camInfo) {
            printf("{\"success\":false,\"error\":\"Failed to get camera info\"}\n");
            enumInfo->Release();
            SCRSDK::Release();
            return 1;
        }

        // Connect
        DummyCallback callback;
        SCRSDK::CrDeviceHandle handle = 0;
        err = SCRSDK::Connect(camInfo, &callback, &handle);
        if (err != SCRSDK::CrError_None) {
            printf("{\"success\":false,\"error\":\"Connect failed: 0x%x\"}\n", err);
            enumInfo->Release();
            SCRSDK::Release();
            return 1;
        }

        // Wait for connection to stabilize
        Sleep(1000);

        if (action == "liveview") {
            // Get live view image
            SCRSDK::CrImageDataBlock imageData;
            memset(&imageData, 0, sizeof(imageData));
            err = SCRSDK::GetLiveViewImage(handle, &imageData);
            if (err == SCRSDK::CrError_None && imageData.GetImageData() && imageData.GetImageSize() > 0) {
                std::string b64 = base64_encode(imageData.GetImageData(), imageData.GetImageSize());
                printf("{\"success\":true,\"base64\":\"%s\",\"size\":%d}\n",
                    b64.c_str(), (int)imageData.GetImageSize());
            } else {
                printf("{\"success\":false,\"error\":\"GetLiveViewImage failed: 0x%x\"}\n", err);
            }
        }

        if (action == "capture") {
            // Trigger capture
            // Use SendCaptureCommand or CrControlCode for shutter
            // For now, use a simpler approach: SetControlDeviceA for capture
            // This is a placeholder — full capture needs event handling
            Sleep(500); // Wait for capture
            printf("{\"success\":false,\"error\":\"Capture not fully implemented yet\"}\n");
        }

        // Disconnect
        SCRSDK::Disconnect(handle);
        Sleep(500);
        enumInfo->Release();
        SCRSDK::Release();
        return 0;
    }

    fprintf(stderr, "Unknown action: %s\n", action.c_str());
    SCRSDK::Release();
    return 1;
}
