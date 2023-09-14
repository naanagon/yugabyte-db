// Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.

#pragma once

#include <atomic>
#include <shared_mutex>

#include "yb/cdc/cdc_service.pb.h"
#include "yb/cdc/cdc_util.h"
#include "yb/common/entity_ids_types.h"
#include "yb/common/hybrid_time.h"
#include "yb/gutil/thread_annotations.h"
#include "yb/master/catalog_entity_info.pb.h"
#include "yb/util/shared_lock.h"

namespace yb::cdc {

// This class holds the metadata for a CDC stream on the Producer cluster. This is a cache of the
// metadata stored in the CatalogManager. Certain fields like table_ids_, state_, ... can change on
// CatalogManager requiring a partial refresh of the cache.
class StreamMetadata {
 public:
  struct StreamTabletMetadata {
    std::mutex mutex_;
    int64_t apply_safe_time_checkpoint_op_id_ GUARDED_BY(mutex_) = 0;
    HybridTime last_apply_safe_time_ GUARDED_BY(mutex_);
    MonoTime last_apply_safe_time_update_time_ GUARDED_BY(mutex_);
    // TODO(hari): #16774 Move last_readable_index and last sent opid here, and use them to make
    // UpdateCDCTabletMetrics run asynchronously.
  };

  // Create an empty StreamMetadata object. InitOrReloadIfNeeded must be called before this can be
  // used.
  StreamMetadata() = default;

  // Create a pre loaded StreamMetadata object.
  StreamMetadata(
      NamespaceId ns_id,
      std::vector<TableId> table_ids,
      CDCRecordType record_type,
      CDCRecordFormat record_format,
      CDCRequestSource source_type,
      CDCCheckpointType checkpoint_type,
      StreamModeTransactional transactional)
      : namespace_id_(std::move(ns_id)),
        record_type_(record_type),
        record_format_(record_format),
        source_type_(source_type),
        checkpoint_type_(checkpoint_type),
        transactional_(transactional),
        loaded_(true),
        table_ids_(std::move(table_ids)) {}

  NamespaceId GetNamespaceId() const {
    DCHECK(loaded_);
    return namespace_id_;
  }
  CDCRecordType GetRecordType() const {
    DCHECK(loaded_);
    return record_type_;
  }
  CDCRecordFormat GetRecordFormat() const {
    DCHECK(loaded_);
    return record_format_;
  }
  CDCRequestSource GetSourceType() const {
    DCHECK(loaded_);
    return source_type_;
  }
  CDCCheckpointType GetCheckpointType() const {
    DCHECK(loaded_);
    return checkpoint_type_;
  }
  master::SysCDCStreamEntryPB_State GetState() const {
    DCHECK(loaded_);
    return state_.load(std::memory_order_acquire);
  }
  StreamModeTransactional IsTransactional() const {
    DCHECK(loaded_);
    return transactional_.load(std::memory_order_acquire);
  }
  std::vector<TableId> GetTableIds() const {
    DCHECK(loaded_);
    SharedLock l(table_ids_mutex_);
    return table_ids_;
  }

  std::shared_ptr<StreamTabletMetadata> GetTabletMetadata(const TabletId& tablet_id)
      EXCLUDES(tablet_metadata_map_mutex_);

  Status InitOrReloadIfNeeded(
      const std::string& stream_id, RefreshStreamMapOption opts, client::YBClient* client)
      EXCLUDES(load_mutex_);

 private:
  Status GetStreamInfoFromMaster(const std::string& stream_id, client::YBClient* client)
      REQUIRES(load_mutex_) EXCLUDES(table_ids_mutex_, tablet_metadata_map_mutex_);

 private:
  NamespaceId namespace_id_;
  CDCRecordType record_type_;
  CDCRecordFormat record_format_;
  CDCRequestSource source_type_;
  CDCCheckpointType checkpoint_type_;
  std::atomic<master::SysCDCStreamEntryPB_State> state_;
  std::atomic<StreamModeTransactional> transactional_{StreamModeTransactional::kFalse};

  std::mutex load_mutex_;  // Used to ensure only a single thread performs InitOrReload.
  std::atomic<bool> loaded_ = false;

  mutable std::shared_mutex table_ids_mutex_;
  std::vector<TableId> table_ids_ GUARDED_BY(table_ids_mutex_);

  std::shared_mutex tablet_metadata_map_mutex_;
  std::unordered_map<TableId, std::shared_ptr<StreamTabletMetadata>> tablet_metadata_map_
      GUARDED_BY(tablet_metadata_map_mutex_);
};

}  // namespace yb::cdc