#include "torrent_system.hpp"
#include <android/log.h>
#include <libtorrent/settings_pack.hpp>
#include <libtorrent/torrent_info.hpp>
#include <libtorrent/add_torrent_params.hpp>
#include <libtorrent/magnet_uri.hpp>
#include <utility>
#include <thread>

#define TAG "StreamX_Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

TorrentSystem::TorrentSystem() : isRunning(false), ses(nullptr) {
    lt::settings_pack pack;
    
    // Enable all discovery methods
    pack.set_bool(lt::settings_pack::enable_dht, true);
    pack.set_bool(lt::settings_pack::enable_lsd, true);
    pack.set_bool(lt::settings_pack::enable_upnp, true);
    pack.set_bool(lt::settings_pack::enable_natpmp, true);
    
    // Use dynamic ports
    pack.set_str(lt::settings_pack::listen_interfaces, "0.0.0.0:0,[::]:0");
    
    // Streaming optimizations
    pack.set_bool(lt::settings_pack::prioritize_partial_pieces, true);
    pack.set_int(lt::settings_pack::metadata_token_limit, 500);
    pack.set_int(lt::settings_pack::alert_mask, lt::alert::all_categories);
    pack.set_int(lt::settings_pack::download_rate_limit, 0);
    pack.set_int(lt::settings_pack::upload_rate_limit, 0);
    pack.set_int(lt::settings_pack::active_downloads, 10);
    pack.set_int(lt::settings_pack::dht_max_peers, 2000); // More DHT peers
    pack.set_int(lt::settings_pack::dht_announce_interval, 60); // Announce every minute
    pack.set_int(lt::settings_pack::dht_bootstrap_nodes, 20); // Keep many bootstrap nodes
    
    // Enhanced DHT bootstrap nodes (more reliable)
    pack.set_str(lt::settings_pack::dht_bootstrap_nodes,
        "router.bittorrent.com:6881,"
        "router.utorrent.com:6881,"
        "dht.transmissionbt.com:6881,"
        "dht.libtorrent.org:25401,"
        "dht.aelitis.com:6881,"
        "dht.metautr.ent:6881,"
        "dht.ikig.ail:6881,"
        "dht.lei.net:6881,"
        "dht.free.isp:6881,"
        "dht.bt.bt:6881,"
        "dht.dnsbl.manitu.net:6881,"
        "dht.moeking.me:6881");
    
    ses = new lt::session(pack);
    
    // Add DHT nodes manually
    ses->add_dht_node(std::make_pair("router.bittorrent.com", 6881));
    ses->add_dht_node(std::make_pair("router.utorrent.com", 6881));
    ses->add_dht_node(std::make_pair("dht.transmissionbt.com", 6881));
    ses->add_dht_node(std::make_pair("dht.libtorrent.org", 25401));
    ses->add_dht_node(std::make_pair("dht.aelitis.com", 6881));
    ses->add_dht_node(std::make_pair("dht.metautr.ent", 6881));
    ses->add_dht_node(std::make_pair("dht.ikig.ail", 6881));
    ses->add_dht_node(std::make_pair("dht.lei.net", 6881));
    ses->add_dht_node(std::make_pair("dht.free.isp", 6881));
    ses->add_dht_node(std::make_pair("dht.bt.bt", 6881));
    
    LOGD("TorrentSystem constructed with aggressive DHT settings");
}

TorrentSystem::~TorrentSystem() {
    stop();
    if (ses) {
        delete ses;
        ses = nullptr;
    }
}

void TorrentSystem::start(const std::string& magnet, const std::string& saveDir) {
    stop();

    isRunning = true;
    finalFilePath = "";
    currentStatus = {0, 0, 0, 0, 0, ""};
    
    lt::error_code ec;
    lt::add_torrent_params p = lt::parse_magnet_uri(magnet, ec);
    if (ec) {
        LOGD("Parse Magnet Error: %s", ec.message().c_str());
        currentStatus.state = 4;
        return;
    }
    
    p.save_path = saveDir;
    p.flags &= ~lt::torrent_flags::paused;
    p.flags &= ~lt::torrent_flags::auto_managed;
    p.flags |= lt::torrent_flags::sequential_download;
    
    // Hardcoded trackers (HTTP first, then UDP)
    std::vector<std::string> extra_trackers = {
        "http://tracker.bt4g.com:2095/announce",
        "http://tracker.files.fm:6969/announce",
        "http://tracker.gbitt.info:80/announce",
        "http://tracker.ipv6tracker.org:80/announce",
        "http://tracker.nyaa.uk:6969/announce",
        "http://tracker.zerobytes.xyz:1337/announce",
        "https://tracker.bt4g.com:443/announce",
        "https://tracker.nanoha.org:443/announce",
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://tracker.openbittorrent.com:80",
        "udp://tracker.coppersurfer.tk:6969",
        "udp://glotorrents.pw:6969/announce",
        "udp://9.rarbg.to:2710",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://tracker.internetwarriors.net:1337/announce",
        "udp://tracker.leechers-paradise.org:6969/announce",
        "udp://tracker.cyberia.is:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://ipv4.tracker.harry.lu:80/announce",
        "udp://tracker.moeking.me:6969/announce",
        "udp://tracker.skynetcloud.tk:6969/announce",
        "udp://tracker.pirateparty.gr:6969/announce",
        "udp://tracker.zerobytes.xyz:1337/announce"
    };
    for (const auto& tr : extra_trackers) {
        p.trackers.push_back(tr);
    }
    
    handle = ses->add_torrent(p, ec);
    if (ec) {
        LOGD("Add Torrent Error: %s", ec.message().c_str());
        currentStatus.state = 4;
        return;
    }
    
    handle.resume();
    handle.force_reannounce();
    handle.force_dht_announce();
    LOGD("Torrent added with %zu trackers", p.trackers.size());

    workerThread = std::thread(&TorrentSystem::updateLoop, this);
}

void TorrentSystem::updateLoop() {
    while (isRunning) {
        if (ses == nullptr || !handle.is_valid()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(500));
            continue;
        }

        lt::torrent_status s = handle.status();

        std::lock_guard<std::mutex> lock(statusMutex);
        
        currentStatus.progress = (s.progress > 0) ? static_cast<int>(s.progress * 100) : 0;
        currentStatus.speed = s.download_rate;
        currentStatus.seeds = s.num_seeds;
        currentStatus.peers = s.num_peers;

        if (!s.has_metadata) {
            currentStatus.state = 1;
            LOGD("State: Fetching metadata, peers: %d", s.num_peers);
            
            // Force announces every 0.5 seconds until we get metadata
            static int counter = 0;
            if (counter++ % 2 == 0) { // every ~0.5 seconds (since loop sleeps 250ms)
                handle.force_reannounce();
                handle.force_dht_announce();
                LOGD("Force reannounce (metadata missing, peers=%d)", s.num_peers);
            }
        }
        else if (s.state == lt::torrent_status::downloading || 
                 s.state == lt::torrent_status::finished || 
                 s.state == lt::torrent_status::seeding) {
            
            if (finalFilePath.empty()) {
                std::shared_ptr<const lt::torrent_info> info = handle.torrent_file();
                if (info && info->is_valid()) {
                    int largestFileIdx = 0;
                    int64_t maxSize = 0;
                    for (int idx = 0; idx < info->num_files(); ++idx) {
                        lt::file_index_t fi(idx);
                        if (info->files().file_size(fi) > maxSize) {
                            maxSize = info->files().file_size(fi);
                            largestFileIdx = idx;
                        }
                    }
                    
                    std::string relPath = info->files().file_path(lt::file_index_t(largestFileIdx));
                    finalFilePath = s.save_path + "/" + relPath;
                    strncpy(currentStatus.videoPath, finalFilePath.c_str(), 511);
                    
                    handle.file_priority(lt::file_index_t(largestFileIdx), lt::default_priority);
                    
                    LOGD("Metadata ready. Video path: %s", finalFilePath.c_str());
                    handle.force_reannounce();
                    handle.force_dht_announce();
                }
            }

            if (currentStatus.progress >= 1 && !finalFilePath.empty()) {
                currentStatus.state = 3;
                LOGD("State: Ready (progress %d%%)", currentStatus.progress);
            } else {
                currentStatus.state = 2;
                LOGD("State: Downloading (progress %d%%)", currentStatus.progress);
            }
        } 
        else {
            currentStatus.state = 0;
            LOGD("State: Idle");
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(250));
    }
}

void TorrentSystem::stop() {
    isRunning = false;
    
    if (workerThread.joinable()) {
        workerThread.join();
    }
    
    if (ses != nullptr && handle.is_valid()) {
        ses->remove_torrent(handle, lt::session::delete_files);
        LOGD("Torrent stopped and files removed");
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