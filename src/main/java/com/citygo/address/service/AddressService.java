package com.citygo.address.service;

import com.citygo.address.dto.AddressCreateRequest;
import com.citygo.address.dto.AddressUpdateRequest;
import com.citygo.address.entity.Address;
import com.citygo.address.vo.AddressVO;

import java.util.List;

/**
 * 收货地址服务接口。
 */
public interface AddressService {

    /**
     * 新增地址；若 isDefault=1，先取消该用户其他默认地址。
     */
    AddressVO create(AddressCreateRequest request, Long currentUserId);

    /**
     * 当前用户地址列表（默认地址优先，再按 id 倒序）。
     */
    List<AddressVO> listMine(Long currentUserId);

    /**
     * 更新地址（归属校验）。
     */
    AddressVO update(Long id, AddressUpdateRequest request, Long currentUserId);

    /**
     * 逻辑删除地址（归属校验）。
     */
    void delete(Long id, Long currentUserId);

    /**
     * 按ID查询地址；不存在返回 null。
     */
    Address getById(Long id);

}