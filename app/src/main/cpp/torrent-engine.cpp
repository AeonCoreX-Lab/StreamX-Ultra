#include "torrent_system.hpp"
#include <android/log.h>
#include <libtorrent/settings_pack.hpp>
#include <libtorrent/torrent_info.hpp>

#define TAG "StreamX_Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

TorrentSystem::TorrentSystem() : isRunning(false) {
    lt::settings_pack pack;
    pack.set_int(lt::settings_pack::alert_mask, 
        lt::alert::status_notification | 
        lt::alert::storage_notification | 
        lt::alert::error_notification);
    
    pack.set_int(lt::settings_pack::download_rate_limit, 0); 
    pack.set_int(lt::settings_pack::upload_rate_limit, 0);
    
    ses = new lt::session(pack);
    memset(&currentStatus, 0, sizeof(EngineStatus));
}

TorrentSystem::~TorrentSystem() {
    stop();
    delete ses;
}

void TorrentSystem::start(const std::string& magnet, const std::string& saveDir) {
    if (isRunning) stop(); // আগের সেশন থাকলে বন্ধ হবে
    
    LOGD("Starting Engine for: %s", magnet.c_str());

    // FIX: State Reset for New Movie
    finalFilePath = ""; 
    memset(&currentStatus, 0, sizeof(EngineStatus));

    lt::add_torrent_params p;
    lt::error_code ec;
    lt::parse_magnet_uri(magnet, p, ec);
    
    if (ec) {
        LOGD("Magnet Parse Error: %s", ec.message().c_str());
        return;
    }

    p.save_path = saveDir;
    p.flags |= lt::torrent_flags::sequential_download;
    
    handle = ses->add_torrent(p);
    
    isRunning = true;
    workerThread = std::thread(&TorrentSystem::updateLoop, this);
}

void TorrentSystem::updateLoop() {
    while (isRunning) {
        std::vector<lt::alert*> alerts;
        ses->pop_alerts(&alerts);

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

        if (s.state == lt::torrent_status::checking_files || s.state == lt::torrent_status::downloading_metadata) {
            currentStatus.state = 1; 
        } 
        else if (s.state == lt::torrent_status::downloading || s.state == lt::torrent_status::finished) {
            
            if (finalFilePath.empty() && s.has_metadata) {
                auto info = s.torrent_file.lock();
                if (info) {
                    lt::file_index_t largestFileIdx;
                    std::int64_t maxSize = 0;
                    
                    for (auto i : info->files().file_range()) {
                        if (info->files().file_size(i) > maxSize) {
                            maxSize = info->files().file_size(i);
                            largestFileIdx = i;
                        }
                    }
                    
                    std::string relPath = info->files().file_path(largestFileIdx);
                    finalFilePath = s.save_path + "/" + relPath;
                    
                    strncpy(currentStatus.videoPath, finalFilePath.c_str(), 511);
                    handle.file_priority(largestFileIdx, lt::default_priority);
                }
            }

            if (currentStatus.progress >= 5 && !finalFilePath.empty()) {
                currentStatus.state = 3; 
            } else {
                currentStatus.state = 2; 
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
        // FIX: Remove Torrent AND Delete Files to prevent conflict with next movie
        ses->remove_torrent(handle, lt::session::delete_files); 
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
