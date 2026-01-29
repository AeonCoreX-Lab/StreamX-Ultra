#include "torrent_system.hpp"
#include <android/log.h>
#include <libtorrent/settings_pack.hpp>
#include <libtorrent/torrent_info.hpp>

#define TAG "StreamX_Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

TorrentSystem::TorrentSystem() : isRunning(false) {
    // সেশন সেটিংস কনফিগার করা
    lt::settings_pack pack;
    pack.set_int(lt::settings_pack::alert_mask, 
        lt::alert::status_notification | 
        lt::alert::storage_notification | 
        lt::alert::error_notification);
    
    // ডাউনলোড স্পিড অপ্টিমাইজেশন
    pack.set_int(lt::settings_pack::download_rate_limit, 0); // Unlimited
    pack.set_int(lt::settings_pack::upload_rate_limit, 0);
    
    ses = new lt::session(pack);
    memset(&currentStatus, 0, sizeof(EngineStatus));
}

TorrentSystem::~TorrentSystem() {
    stop();
    delete ses;
}

void TorrentSystem::start(const std::string& magnet, const std::string& saveDir) {
    if (isRunning) stop();
    
    LOGD("Starting Engine for: %s", magnet.c_str());

    lt::add_torrent_params p;
    lt::error_code ec;
    lt::parse_magnet_uri(magnet, p, ec);
    
    if (ec) {
        LOGD("Magnet Parse Error: %s", ec.message().c_str());
        return;
    }

    p.save_path = saveDir;
    
    // CRITICAL FOR STREAMING: ক্রম অনুসারে ডাউনলোড করা
    p.flags |= lt::torrent_flags::sequential_download;
    
    handle = ses->add_torrent(p);
    
    isRunning = true;
    workerThread = std::thread(&TorrentSystem::updateLoop, this);
}

void TorrentSystem::updateLoop() {
    while (isRunning) {
        std::vector<lt::alert*> alerts;
        ses->pop_alerts(&alerts);

        for (lt::alert const* a : alerts) {
            // ডিবাগিং এর জন্য লগ
            // LOGD("Alert: %s", a->message().c_str());
        }

        if (!handle.is_valid()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(500));
            continue;
        }

        lt::torrent_status s = handle.status();

        std::lock_guard<std::mutex> lock(statusMutex);
        currentStatus.progress = (int)(s.progress * 100);
        currentStatus.speed = s.download_payload_rate;
        currentStatus.seeds = s.num_seeds;
        currentStatus.peers = s.num_peers;

        // স্টেট ম্যানেজমেন্ট
        if (s.state == lt::torrent_status::checking_files || s.state == lt::torrent_status::downloading_metadata) {
            currentStatus.state = 1; // Preparing
        } 
        else if (s.state == lt::torrent_status::downloading || s.state == lt::torrent_status::finished) {
            
            // ফাইলের নাম/পাথ বের করা (একবার মেটাডাটা ডাউনলোড হলে)
            if (finalFilePath.empty() && s.has_metadata) {
                auto info = s.torrent_file.lock();
                if (info) {
                    // সবচেয়ে বড় ফাইলটি ভিডিও হিসেবে ধরে নেওয়া হচ্ছে
                    lt::file_index_t largestFileIdx;
                    std::int64_t maxSize = 0;
                    
                    for (auto i : info->files().file_range()) {
                        if (info->files().file_size(i) > maxSize) {
                            maxSize = info->files().file_size(i);
                            largestFileIdx = i;
                        }
                    }
                    
                    std::string relPath = info->files().file_path(largestFileIdx);
                    finalFilePath = handle.save_path() + "/" + relPath;
                    strncpy(currentStatus.videoPath, finalFilePath.c_str(), 511);
                    LOGD("Video File Path Identified: %s", finalFilePath.c_str());
                    
                    // নির্দিষ্ট ভিডিও ফাইলের প্রায়োরিটি সর্বোচ্চ করা
                    handle.file_priority(largestFileIdx, lt::default_priority);
                }
            }

            // বাফার লজিক: ৫% ডাউনলোড হলেই ভিডিও প্লে শুরু
            if (currentStatus.progress >= 5 && !finalFilePath.empty()) {
                currentStatus.state = 3; // Ready to Play
            } else {
                currentStatus.state = 2; // Downloading
            }
        } 
        else {
            currentStatus.state = 0;
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(1000));
    }
}

void TorrentSystem::stop() {
    isRunning = false;
    if (workerThread.joinable()) workerThread.join();
    if (handle.is_valid()) {
        ses->remove_torrent(handle);
    }
}

EngineStatus TorrentSystem::getStatus() {
    std::lock_guard<std::mutex> lock(statusMutex);
    return currentStatus;
}

std::string TorrentSystem::getFilePath() {
    std::lock_guard<std::mutex> lock(statusMutex);
    return finalFilePath;
}
