/*
 * Copyright 2021 Green Mushroom
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.gm.cleaner.plugin.xposed

import me.gm.cleaner.plugin.model.Templates
import java.io.File

class TemplatesJsonFileSpImpl(src: File) : JsonFileSpImpl(src) {
    @Volatile
    var templates: Templates = Templates(read())
        internal set

    override fun write(what: String) {
        super.write(what)
        // Clear cache; the caller (ManagerService.writeSp) reassigns
        // templates with remoteValues immediately after, so we skip
        // creating an intermediate Templates without remote data.
        templates.clearCache()
    }
}
