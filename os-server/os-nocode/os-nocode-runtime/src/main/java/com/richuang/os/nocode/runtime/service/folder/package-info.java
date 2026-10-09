/**
 * 记录文件夹：对象上配「文件夹来源」，每个来源对一条记录解析出网盘里的一个真实文件夹，表单下方把网盘的浏览器嵌进来、根锁定在那个文件夹。 本包只做配置、解析、命名、
 * 鉴权与提交后的后台建立；文件操作全部经 {@code DriveFolderApi} 转给网盘，不另做一套。不改记录保存、删除与附件归档三条链路。
 */
package com.richuang.os.nocode.runtime.service.folder;
