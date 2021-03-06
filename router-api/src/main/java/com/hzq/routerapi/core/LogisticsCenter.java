package com.hzq.routerapi.core;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import com.hzq.router_annotation.facade.enums.TypeKind;
import com.hzq.router_annotation.facade.model.RouteMeta;
import com.hzq.routerapi.exception.HandlerException;
import com.hzq.routerapi.exception.NoRouteFoundException;
import com.hzq.routerapi.facade.template.IProvider;
import com.hzq.routerapi.facade.template.IProviderGroup;
import com.hzq.routerapi.facade.template.IRouteGroup;
import com.hzq.routerapi.facade.template.IRouteRoot;
import com.hzq.routerapi.log.ILogger;
import com.hzq.routerapi.util.ClassUtils;
import com.hzq.routerapi.util.Consts;
import com.hzq.routerapi.util.MapUtils;
import com.hzq.routerapi.util.PackageUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.hzq.routerapi.util.Consts.TAG;

/**
 * Created by hezhiqiang on 2018/11/20.
 */

public class LogisticsCenter {
    private static ILogger logger;

    public synchronized static void init(Context context, ILogger log) {
        logger = log;
        Set<String> routeMap;
        try {
            if(RouterManager.debuggable() || PackageUtils.isNewVersion(context)) { //开发模式或版本升级时扫描本地件
                logger.info(TAG,"当前环境为debug模式或者新版本，需要重新生成映射关系表");
                //these class was generated by router-compiler
                routeMap = ClassUtils.getFileNameByPackageName(context, Consts.ROUTE_ROOT_PAKCAGE);
                if(!routeMap.isEmpty()) {
                    PackageUtils.put(context,Consts.ROUTER_SP_KEY_MAP,routeMap);
                }
                PackageUtils.updateVersion(context);
            } else{ //读取缓存
                logger.info(TAG,"读取缓存中的router映射表");
                routeMap = PackageUtils.get(context,Consts.ROUTER_SP_KEY_MAP);
            }

            logger.info(TAG,"router map 扫描完成");
            //将分组数据加载到内存
            for (String className : routeMap) {
                //Root
                if(className.startsWith(Consts.ROUTE_ROOT_PAKCAGE + Consts.DOT + Consts.SDK_NAME + Consts.SEPARATOR + Consts.SUFFIX_ROOT)) {
                    ((IRouteRoot)(Class.forName(className).getConstructor().newInstance())).loadInto(Warehouse.groupsIndex);
                } else if(className.startsWith(Consts.ROUTE_ROOT_PAKCAGE + Consts.DOT + Consts.SDK_NAME + Consts.SEPARATOR + Consts.SUFFIX_PROVIDERS)) { //provider
                    ((IProviderGroup)(Class.forName(className).getConstructor().newInstance())).loadInto(Warehouse.providersIndex);
                }
            }

            logger.info(TAG,"将映射关系读到缓存中");

            if(Warehouse.groupsIndex.size() == 0) {
                logger.error(TAG,"No mapping files,check your configuration please!");
            }

            if (RouterManager.debuggable()) {
                logger.debug(TAG, String.format(Locale.getDefault(), "LogisticsCenter has already been loaded, GroupIndex[%d], ProviderIndex[%d]", Warehouse.groupsIndex.size(), Warehouse.providersIndex.size()));
            }

        } catch (Exception e) {
            e.printStackTrace();
            logger.error(TAG,"RouterManager init logistics center exception! [" + e.getMessage() + "]");
        }
    }

    /**
     * Build postcard by serviceName
     *
     * @param serviceName interfaceName
     * @return postcard
     */
    public static Postcard buildProvider(String serviceName) {
        RouteMeta meta = Warehouse.providersIndex.get(serviceName);

        if (null == meta) {
            return null;
        } else {
            return new Postcard(meta.getPath(), meta.getGroup());
        }
    }

    /**
     * 填充数据
     * @param postcard
     */
    public synchronized static void completion(Postcard postcard) {
        RouteMeta routeMeta = Warehouse.routes.get(postcard.getPath());
        if(routeMeta != null) {
            postcard.setDestination(routeMeta.getDestination());
            postcard.setType(routeMeta.getType());
            postcard.setPriority(routeMeta.getPriority());
            postcard.setExtra(routeMeta.getExtra());

            Uri rawUri = postcard.getUri();
            //装载参数
            if(rawUri != null) {
                Map<String,String> resultMap = splitQueryParameters(rawUri);
                Map<String,Integer> paramsType = routeMeta.getParamsType();

                if(MapUtils.isNotEmpty(paramsType)) {
                    for (Map.Entry<String, Integer> params : paramsType.entrySet()) {
                        setValue(postcard,
                                params.getValue(),
                                params.getKey(),
                                resultMap.get(params.getKey()));
                    }
                }
            }

            switch (routeMeta.getType()) {
                case PROVIDER: //服务
                    Class<? extends IProvider> providerMeta = (Class<? extends IProvider>) routeMeta.getDestination();
                    IProvider instance = Warehouse.providers.get(providerMeta);
                    if(instance == null) {
                        IProvider provider;
                        try {
                            provider = providerMeta.getConstructor().newInstance();
                            Warehouse.providers.put(providerMeta,provider);
                            instance = provider;
                        } catch (Exception e) {
                            throw new HandlerException("Init provider failed! " + e.getMessage());
                        }
                    }
                    postcard.setProvider(instance);
                    postcard.greenChannel();
                    break;
                case FRAGMENT: //fragment
                    postcard.greenChannel();
                    break;

                default:
                    break;
            }
        } else {
            Class<? extends IRouteGroup> groupMeta = Warehouse.groupsIndex.get(postcard.getGroup());
            if(groupMeta == null) {
                throw new NoRouteFoundException("There is no route match the path [" + postcard.getPath() + "], in group [" + postcard.getGroup() + "]");
            } else {
                try {
                    //按组加载数据，美其名曰-按需加载
                    IRouteGroup iRouteGroup = groupMeta.getConstructor().newInstance();
                    iRouteGroup.loadInto(Warehouse.routes);
                    Warehouse.groupsIndex.remove(postcard.getGroup());
                } catch (Exception e) {
                    throw new HandlerException("Fatal exception when loading group meta. [" + e.getMessage() + "]");
                }
            }

            completion(postcard); //分组加载完成后重新查找
        }
    }

    /**
     * Split query parameters
     * @param rawUri raw uri
     * @return map with params
     */
    private static Map<String, String> splitQueryParameters(Uri rawUri) {
        String query = rawUri.getEncodedQuery();

        if (query == null) {
            return Collections.emptyMap();
        }

        Map<String, String> paramMap = new LinkedHashMap<>();
        int start = 0;
        do {
            int next = query.indexOf('&', start);
            int end = (next == -1) ? query.length() : next;

            int separator = query.indexOf('=', start);
            if (separator > end || separator == -1) {
                separator = end;
            }

            String name = query.substring(start, separator);

            if (!android.text.TextUtils.isEmpty(name)) {
                String value = (separator == end ? "" : query.substring(separator + 1, end));
                paramMap.put(Uri.decode(name), Uri.decode(value));
            }

            // Move start to end of name.
            start = end + 1;
        } while (start < query.length());

        return Collections.unmodifiableMap(paramMap);
    }

    /**
     * Set value by known type
     *
     * @param postcard postcard
     * @param typeDef  type
     * @param key      key
     * @param value    value
     */
    private static void setValue(Postcard postcard, Integer typeDef, String key, String value) {
        if (TextUtils.isEmpty(key) || TextUtils.isEmpty(value)) {
            return;
        }

        try {
            if (null != typeDef) {
                if (typeDef == TypeKind.BOOLEAN.ordinal()) {
                    postcard.withBoolean(key, Boolean.parseBoolean(value));
                } else if (typeDef == TypeKind.BYTE.ordinal()) {
                    postcard.withByte(key, Byte.valueOf(value));
                } else if (typeDef == TypeKind.SHORT.ordinal()) {
                    postcard.withShort(key, Short.valueOf(value));
                } else if (typeDef == TypeKind.INT.ordinal()) {
                    postcard.withInt(key, Integer.valueOf(value));
                } else if (typeDef == TypeKind.LONG.ordinal()) {
                    postcard.withLong(key, Long.valueOf(value));
                } else if (typeDef == TypeKind.FLOAT.ordinal()) {
                    postcard.withFloat(key, Float.valueOf(value));
                } else if (typeDef == TypeKind.DOUBLE.ordinal()) {
                    postcard.withDouble(key, Double.valueOf(value));
                } else if (typeDef == TypeKind.STRING.ordinal()) {
                    postcard.withString(key, value);
                } else if (typeDef == TypeKind.PARCELABLE.ordinal()) {
                    // TODO : How to description parcelable value with string?
                } else if (typeDef == TypeKind.OBJECT.ordinal()) {
                    postcard.withString(key, value);
                } else {    // Compatible compiler sdk 1.0.3, in that version, the string type = 18
                    postcard.withString(key, value);
                }
            } else {
                postcard.withString(key, value);
            }
        } catch (Throwable ex) {
            logger.warning(TAG, "LogisticsCenter setValue failed! " + ex.getMessage());
        }
    }

    public static void clear() {
        Warehouse.clear();
    }
}
