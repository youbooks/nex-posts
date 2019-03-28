1. 普通票  value = 100$ max_using_times = 1
2. 扣钱券 value = 100$ max_using_times = Integer.MAX_VALUE()(表示无数次)
3. 刷次数券 value = 100$ max_using_times = 5(限定次数)


刷次数券 核销时 传到后端的 minusValue 字段 值为 0
