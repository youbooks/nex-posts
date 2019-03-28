###lua代码

```lua
# init_r.lua
local shared_data = ngx.shared.dict
shared_data:set("draw", 0)

# draw_r.lua
local request_uri = ngx.var.request_uri;
if string.sub(request_uri,1,22) == "/activity/lottery/draw" then
local val, err = ngx.shared.dict:incr("draw", 1); #进来一个一个请求就加1
if val > 100 then #限流100
ngx.say("{\"success\" : true,\"data\" : {\"awardType\" : \"00\" }}")
ngx.log(ngx.ERR,"draw limit val is:"..val)
return ngx.exit(200)
end
return
end

# draw_decr.lua
local request_uri = ngx.var.request_uri;
if string.sub(request_uri,1,22) == "/activity/lottery/draw" then
local newval, err = ngx.shared.dict:incr("draw", -1); #一个请求完成就减一
if newval < 0 then
ngx.shared.dict:set("draw", 0);
end
return
end
```

### nginx中配置

```conf
init_by_lua_file /etc/nginx/init_r.lua;


location / {
        default_type application/json;
        rewrite_by_lua_file /etc/nginx/draw_r.lua;
        log_by_lua_file /etc/nginx/draw_decr.lua;
  }
```





参考:https://segmentfault.com/a/1190000007468421