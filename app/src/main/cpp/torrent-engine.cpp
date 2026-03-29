#include "torrent_system.hpp"
#include <android/log.h>
#include <libtorrent/settings_pack.hpp>
#include <libtorrent/torrent_info.hpp>
#include <libtorrent/add_torrent_params.hpp>
#include <libtorrent/magnet_uri.hpp>
#include <libtorrent/file_storage.hpp>
#include <utility>
#include <thread>
#include <algorithm>

#define TAG "StreamX_Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

TorrentSystem::TorrentSystem() : isRunning(false), ses(nullptr) {
    lt::settings_pack pack;
    pack.set_bool(lt::settings_pack::enable_dht,    true);
    pack.set_bool(lt::settings_pack::enable_lsd,    true);
    pack.set_bool(lt::settings_pack::enable_upnp,   true);
    pack.set_bool(lt::settings_pack::enable_natpmp, true);
    pack.set_str(lt::settings_pack::listen_interfaces, "0.0.0.0:0,[::]:0");
    pack.set_bool(lt::settings_pack::prioritize_partial_pieces, true);
    pack.set_int(lt::settings_pack::alert_mask,           lt::alert::all_categories);
    pack.set_int(lt::settings_pack::download_rate_limit,  0);
    pack.set_int(lt::settings_pack::upload_rate_limit,    0);
    pack.set_int(lt::settings_pack::active_downloads,     10);
    pack.set_int(lt::settings_pack::metadata_token_limit, 2048);
    pack.set_int(lt::settings_pack::dht_max_peers,        2000);
    pack.set_int(lt::settings_pack::dht_announce_interval, 60);
    pack.set_str(lt::settings_pack::dht_bootstrap_nodes,
        "router.bittorrent.com:6881,"
        "router.utorrent.com:6881,"
        "dht.transmissionbt.com:6881,"
        "dht.libtorrent.org:25401");

    ses = new lt::session(pack);
    ses->add_dht_node({"router.bittorrent.com",  6881});
    ses->add_dht_node({"router.utorrent.com",    6881});
    ses->add_dht_node({"dht.transmissionbt.com", 6881});
    ses->add_dht_node({"dht.libtorrent.org",    25401});
    LOGD("TorrentSystem constructed");
}

TorrentSystem::~TorrentSystem() {
    stop();
    if (ses) { delete ses; ses = nullptr; }
}

void TorrentSystem::start(const std::string& magnet, const std::string& saveDir) {
    stop();
    isRunning     = true;
    finalFilePath = "";
    firstPieceIdx = -1;
    lastPieceIdx  = -1;
    currentStatus = {0, 0, 0, 0, 0, ""};

    lt::error_code ec;
    lt::add_torrent_params p = lt::parse_magnet_uri(magnet, ec);
    if (ec) { LOGE("Parse magnet: %s", ec.message().c_str()); currentStatus.state = 4; return; }

    p.save_path  = saveDir;
    p.flags     &= ~lt::torrent_flags::paused;
    p.flags     &= ~lt::torrent_flags::auto_managed;
    p.flags     |=  lt::torrent_flags::sequential_download;

    static const std::vector<std::string> trackers = {
        "http://tracker.bt4g.com:2095/announce",
        "http://tracker.files.fm:6969/announce",
        "http://tracker.gbitt.info:80/announce",
        "https://tracker.bt4g.com:443/announce",
        "https://tracker.nanoha.org:443/announce",
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://tracker.openbittorrent.com:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://tracker.leechers-paradise.org:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.moeking.me:6969/announce"
    };
    for (const auto& tr : trackers) p.trackers.push_back(tr);

    handle = ses->add_torrent(p, ec);
    if (ec) { LOGE("Add torrent: %s", ec.message().c_str()); currentStatus.state = 4; return; }

    handle.resume();
    handle.force_reannounce();
    handle.force_dht_announce();
    LOGD("Torrent added (%zu trackers)", p.trackers.size());
    workerThread = std::thread(&TorrentSystem::updateLoop, this);
}

void TorrentSystem::updateLoop() {
    int reannounce_ctr = 0;

    while (isRunning) {
        if (!ses || !handle.is_valid()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(500));
            continue;
        }

        lt::torrent_status s = handle.status();
        std::lock_guard<std::mutex> lock(statusMutex);

        currentStatus.progress = (s.progress > 0) ? static_cast<int>(s.progress * 100) : 0;
        currentStatus.speed    = s.download_rate;
        currentStatus.seeds    = s.num_seeds;
        currentStatus.peers    = s.num_peers;

        if (!s.has_metadata) {
            currentStatus.state = 1;
            if (++reannounce_ctr % 8 == 0) {
                handle.force_reannounce();
                handle.force_dht_announce();
            }
            LOGD("Metadata: peers=%d", s.num_peers);
        }
        else if (s.state == lt::torrent_status::downloading ||
                 s.state == lt::torrent_status::finished    ||
                 s.state == lt::torrent_status::seeding) {

            // ── Identify video file once ─────────────────────
            if (finalFilePath.empty()) {
                std::shared_ptr<const lt::torrent_info> info = handle.torrent_file();
                if (info && info->is_valid()) {
                    int     largestIdx = 0;
                    int64_t maxSize    = 0;
                    for (int idx = 0; idx < info->num_files(); ++idx) {
                        lt::file_index_t fi(idx);
                        int64_t sz = info->files().file_size(fi);
                        if (sz > maxSize) { maxSize = sz; largestIdx = idx; }
                    }
                    lt::file_index_t largest_fi(largestIdx);
                    finalFilePath = s.save_path + "/" + info->files().file_path(largest_fi);
                    strncpy(currentStatus.videoPath, finalFilePath.c_str(), 511);

                    lt::peer_request pr_first = info->map_file(largest_fi, 0, 1);
                    lt::peer_request pr_last  = info->map_file(largest_fi, maxSize - 1, 1);
                    firstPieceIdx = (int)pr_first.piece;
                    lastPieceIdx  = (int)pr_last.piece;

                    // Top priority: first 50 + last 20 pieces
                    for (int i = 0; i < 50 && firstPieceIdx + i <= lastPieceIdx; ++i)
                        handle.piece_priority(lt::piece_index_t(firstPieceIdx + i), lt::top_priority);
                    for (int i = 0; i < 20 && lastPieceIdx - i >= firstPieceIdx; ++i)
                        handle.piece_priority(lt::piece_index_t(lastPieceIdx - i), lt::top_priority);

                    handle.file_priority(largest_fi, lt::default_priority);
                    LOGD("Video: %s  pieces[%d..%d]", finalFilePath.c_str(), firstPieceIdx, lastPieceIdx);
                    handle.force_reannounce();
                    handle.force_dht_announce();
                }
            }

            // ────────────────────────────────────────────────────────────
            //  Ready gate: dual condition before handing file to MPV.
            //
            //  MIN_PROGRESS: 5% → 8%
            //
            //  WHY INCREASED:
            //  At 5% of a 4GB 1080p file = 200MB.
            //  At 4Mbps bitrate, 200MB = 400 seconds of content.
            //  At 588 KB/s (4.7Mbps) download vs 4Mbps playback, margin is
            //  only 0.7Mbps → very thin buffer against speed fluctuations.
            //  If download drops briefly below 4Mbps (peers slow), MPV
            //  hits the frontier → paused-for-cache → "froze after minutes".
            //
            //  At 8% = 320MB / 640 seconds at 4Mbps.
            //  This gives ~2× more safety margin before the first frontier hit.
            //  The extra 3% (120MB) takes about 200 extra seconds to download
            //  at 588KB/s — a worthwhile trade for smooth playback.
            //
            //  NOTE: cache-pause-initial=yes in MPV also helps recover from
            //  frontier hits by automatically pausing+buffering when they occur.
            // ────────────────────────────────────────────────────────────

            static const int HEADER_PIECES = 30;
            static const int MIN_PROGRESS  = 8;   // ← changed from 5

            if (!finalFilePath.empty() && firstPieceIdx >= 0) {
                bool progressOk = (currentStatus.progress >= MIN_PROGRESS);

                int toCheck = std::min(HEADER_PIECES, lastPieceIdx - firstPieceIdx + 1);
                int ready   = 0;
                for (int i = 0; i < toCheck; ++i)
                    if (handle.have_piece(lt::piece_index_t(firstPieceIdx + i))) ++ready;
                bool headerOk = (ready >= toCheck);

                if (progressOk && headerOk) {
                    currentStatus.state = 3;
                    LOGD("READY  progress=%d%%  header=%d/%d  speed=%ld",
                         currentStatus.progress, ready, toCheck, currentStatus.speed);
                } else {
                    currentStatus.state = 2;
                    LOGD("Buffering  progress=%d%%  header=%d/%d",
                         currentStatus.progress, ready, toCheck);
                }
            } else {
                currentStatus.state = 2;
            }
        }
        else {
            currentStatus.state = 0;
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(250));
    }
}

void TorrentSystem::stop() {
    isRunning = false;
    if (workerThread.joinable()) workerThread.join();
    if (ses && handle.is_valid()) {
        ses->remove_torrent(handle, lt::session::delete_files);
        LOGD("Torrent stopped");
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