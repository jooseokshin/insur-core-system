package com.insur.auth.controller;

import com.insur.auth.common.CommonHeader;
import com.insur.auth.common.CommonMessage;
import com.insur.auth.service.EmployeeAdminService;
import com.insur.auth.service.dto.EmployeeDto;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사원·권한 관리. ADMIN 역할만 호출할 수 있다(SecurityConfig). 처리 사번은 토큰의 subject 다.
 */
@RestController
@RequestMapping("/auth/admin")
@RequiredArgsConstructor
public class EmployeeAdminController {

    private final EmployeeAdminService adminService;

    @PostMapping("/emp-list")
    public CommonMessage<EmployeeDto.ListResponse> list(@RequestBody CommonMessage<EmployeeDto.ListRequest> request) {
        return CommonMessage.success(request.getHeader(), adminService.list(request.requireBody()));
    }

    @PostMapping("/emp-detail")
    public CommonMessage<EmployeeDto.DetailResponse> detail(@RequestBody CommonMessage<EmployeeDto.KeyRequest> request) {
        return CommonMessage.success(request.getHeader(), adminService.detail(request.requireBody().getEmpId()));
    }

    @PostMapping("/emp-create")
    public CommonMessage<EmployeeDto.TemporaryPasswordResponse> create(
            @RequestBody CommonMessage<EmployeeDto.CreateRequest> request) {
        CommonHeader header = request.getHeader();
        return CommonMessage.success(header, adminService.create(request.requireBody(), header.getEmpId(), header.getGlobalTxId()));
    }

    @PostMapping("/emp-update")
    public CommonMessage<EmployeeDto.Item> update(@RequestBody CommonMessage<EmployeeDto.UpdateRequest> request) {
        CommonHeader header = request.getHeader();
        return CommonMessage.success(header, adminService.update(request.requireBody(), header.getEmpId(), header.getGlobalTxId()));
    }

    @PostMapping("/emp-reset-password")
    public CommonMessage<EmployeeDto.TemporaryPasswordResponse> resetPassword(
            @RequestBody CommonMessage<EmployeeDto.KeyRequest> request) {
        CommonHeader header = request.getHeader();
        return CommonMessage.success(header,
                adminService.resetPassword(request.requireBody().getEmpId(), header.getEmpId(), header.getGlobalTxId()));
    }

    @PostMapping("/emp-unlock")
    public CommonMessage<EmployeeDto.Item> unlock(@RequestBody CommonMessage<EmployeeDto.KeyRequest> request) {
        CommonHeader header = request.getHeader();
        return CommonMessage.success(header,
                adminService.unlock(request.requireBody().getEmpId(), header.getEmpId(), header.getGlobalTxId()));
    }

    @PostMapping("/login-history")
    public CommonMessage<EmployeeDto.LoginHistoryResponse> loginHistory(
            @RequestBody CommonMessage<EmployeeDto.LoginHistoryRequest> request) {
        return CommonMessage.success(request.getHeader(), adminService.loginHistory(request.requireBody()));
    }
}
