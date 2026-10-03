/*
 * Copyright (C) 2026 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Forward-compatible enum support.
 *
 * <p>Wrap enum properties in {@link com.google.gson.enums.EnumValue} and register {@link
 * com.google.gson.enums.EnumValueTypeAdapterFactory} on the {@code GsonBuilder} so that unknown
 * enum strings added by newer senders are preserved instead of silently becoming {@code null},
 * while explicit JSON {@code null} remains distinguishable. Plain (non-wrapped) enum types keep
 * their default Gson behavior.
 */
package com.google.gson.enums;
