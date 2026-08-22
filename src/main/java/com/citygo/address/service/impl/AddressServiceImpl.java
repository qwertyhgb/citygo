package com.citygo.address.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.citygo.address.dto.AddressCreateRequest;
import com.citygo.address.dto.AddressUpdateRequest;
import com.citygo.address.entity.Address;
import com.citygo.address.mapper.AddressMapper;
import com.citygo.address.service.AddressService;
import com.citygo.address.vo.AddressVO;
import com.citygo.common.exception.BizException;
import com.citygo.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 收货地址服务实现。
 */
@Service
public class AddressServiceImpl implements AddressService {

    private final AddressMapper addressMapper;

    public AddressServiceImpl(AddressMapper addressMapper) {
        this.addressMapper = addressMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AddressVO create(AddressCreateRequest request, Long currentUserId) {
        Address address = new Address();
        address.setUserId(currentUserId);
        address.setReceiverName(request.getReceiverName());
        address.setReceiverPhone(request.getReceiverPhone());
        address.setProvince(request.getProvince());
        address.setCity(request.getCity());
        address.setDistrict(request.getDistrict());
        address.setDetailAddress(request.getDetailAddress());
        int isDefault = request.getIsDefault() == null ? 0 : request.getIsDefault();
        address.setIsDefault(isDefault);
        // 默认地址唯一性：若新地址是默认，先把该用户旧默认清掉，保证同时只有一个默认地址
        if (isDefault == 1) {
            clearDefault(currentUserId);
        }
        addressMapper.insert(address);
        return toVO(address);
    }

    @Override
    public List<AddressVO> listMine(Long currentUserId) {
        return addressMapper.selectList(
                        Wrappers.<Address>lambdaQuery()
                                .eq(Address::getUserId, currentUserId)
                                .orderByDesc(Address::getIsDefault)
                                .orderByDesc(Address::getId))
                .stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AddressVO update(Long id, AddressUpdateRequest request, Long currentUserId) {
        Address address = requireOwned(id, currentUserId);
        address.setReceiverName(request.getReceiverName());
        address.setReceiverPhone(request.getReceiverPhone());
        address.setProvince(request.getProvince());
        address.setCity(request.getCity());
        address.setDistrict(request.getDistrict());
        address.setDetailAddress(request.getDetailAddress());
        if (request.getIsDefault() != null) {
            int isDefault = request.getIsDefault();
            address.setIsDefault(isDefault);
            if (isDefault == 1) {
                // 若设为默认，取消其他默认
                clearDefault(currentUserId);
            }
        }
        addressMapper.updateById(address);
        return toVO(address);
    }

    @Override
    public void delete(Long id, Long currentUserId) {
        // 归属校验通过后 deleteById（MP 逻辑删除，实际执行 UPDATE deleted=1）
        requireOwned(id, currentUserId);
        addressMapper.deleteById(id);
    }

    @Override
    public Address getById(Long id) {
        return addressMapper.selectById(id);
    }

    /**
     * 把当前用户所有地址的 is_default 置 0（保证默认地址唯一）。
     */
    private void clearDefault(Long userId) {
        addressMapper.update(null, Wrappers.<Address>lambdaUpdate()
                .eq(Address::getUserId, userId)
                .set(Address::getIsDefault, 0));
    }

    /**
     * 查询地址并做归属校验：不存在抛 ADDRESS_NOT_FOUND，非本人地址抛越权。
     */
    private Address requireOwned(Long id, Long currentUserId) {
        Address address = addressMapper.selectById(id);
        if (address == null) {
            throw new BizException(ErrorCode.ADDRESS_NOT_FOUND);
        }
        if (!address.getUserId().equals(currentUserId)) {
            throw new BizException(ErrorCode.UNAUTHORIZED_OPERATION);
        }
        return address;
    }

    /**
     * 地址实体 → 视图对象。
     */
    private AddressVO toVO(Address address) {
        AddressVO vo = new AddressVO();
        vo.setId(address.getId());
        vo.setReceiverName(address.getReceiverName());
        vo.setReceiverPhone(address.getReceiverPhone());
        vo.setProvince(address.getProvince());
        vo.setCity(address.getCity());
        vo.setDistrict(address.getDistrict());
        vo.setDetailAddress(address.getDetailAddress());
        vo.setIsDefault(address.getIsDefault());
        return vo;
    }

}